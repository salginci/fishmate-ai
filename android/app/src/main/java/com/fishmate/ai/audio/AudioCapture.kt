package com.fishmate.ai.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.fishmate.ai.core.Log2
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.Volatile

/**
 * Microphone capture producing exactly what the Live API expects:
 * 16-bit PCM, 16 kHz, mono, little-endian.
 *
 * `ENCODING_PCM_16BIT` on Android is already little-endian, and we request
 * 16 kHz directly from [AudioRecord], so no resampling is needed — which
 * matters, because resampling on the audio thread would add latency to every
 * utterance.
 *
 * `VOICE_COMMUNICATION` is chosen deliberately over `MIC`: it enables the
 * platform's echo cancellation and noise suppression. Without echo
 * cancellation the model hears its own voice through the phone speaker and
 * interrupts itself continuously — the single most important audio decision in
 * this app (docs/GEMINI_LIVE.md -> "Audio").
 */
class AudioCapture(
    private val sampleRateHz: Int = 16_000,
    /**
     * 20 ms of audio per chunk. Small chunks keep latency low; the Live API
     * guidance is explicitly to avoid large buffers.
     */
    private val chunkMillis: Int = 20,
) {
    private val bytesPerSample = 2
    private val chunkBytes: Int = sampleRateHz * bytesPerSample * chunkMillis / 1000

    @Volatile
    private var record: AudioRecord? = null

    @Volatile
    var isMuted: Boolean = false

    /**
     * Suppress outgoing audio while the assistant is speaking.
     *
     * On a loudspeaker the microphone hears the model's own voice. Server-side
     * VAD reads that as the user barging in, so the model stops, restarts, and
     * hears itself again — a loop that interrupts roughly every three seconds
     * and transcribes the garbled echo as random languages.
     *
     * Platform echo cancellation helps but does not win against a phone
     * speaker at arm's length, so while this is set each frame must clear the
     * echo-level test in [EchoGate] before it is forwarded. The
     * controller only sets it when the output really is the built-in speaker;
     * with earbuds there is no acoustic path and capture runs wide open.
     */
    @Volatile
    var suppressWhileSpeaking: Boolean = false

    /**
     * How loud the speaker is right now, 0..1 — the far-end reference the
     * gate needs to tell the assistant's leak apart from the angler.
     *
     * A lambda rather than a value because it is read per 20 ms frame on the
     * capture thread, and because wiring [AudioPlayer] straight into capture
     * would couple the two halves of the pipeline for one number.
     */
    @Volatile
    var speakerLevel: () -> Float = { 0f }

    /**
     * Called on the capture thread the moment the gate accepts a barge-in.
     *
     * The server's own interruption arrives a round trip later; this is what
     * lets playback stop while the angler is still saying their first word.
     */
    @Volatile
    var onBargeIn: () -> Unit = {}

    private val echoGate = EchoGate()
    private val canceller = EchoCanceller()

    /** Where playback has reached, for aligning [canceller]. */
    @Volatile
    var playbackFrame: () -> Long = { 0L }

    /** Far-end audio, as the player commits it to the speaker. */
    fun pushFarEnd(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        canceller.pushFarEnd(pcm, offset, length, startFrame)
    }

    /**
     * Whether the gate has accepted a barge-in for the turn being spoken.
     *
     * Read by the controller while it holds the speaker down: that wait ends
     * either because a voice cleared the bar with the leak out of the way, or
     * because nothing did.
     */
    val isBargeInOpen: Boolean get() = echoGate.isBargeInOpen

    /**
     * Which capture owns the microphone.
     *
     * One [AudioCapture] is shared for the life of the process, so a second
     * Fishing Mode session reuses this object — and the gate inside it. Two
     * capture loops running at once were measured on the device: the first
     * session's loop outlived its collector, the second opened its own
     * `AudioRecord` on top, and both fed frames into the single [echoGate] at
     * twice the expected rate. Its calibration counters, its room floor and
     * its per-turn decay are all per-frame, so every one of them ran at double
     * speed against two different signals; the gate's own diagnostics gave it
     * away by printing twice a second instead of once.
     *
     * A newer loop claims the microphone by bumping this. An older loop sees
     * the number has moved and stands down, which is a check it makes itself
     * rather than something cancellation has to deliver.
     */
    private val captureGeneration = AtomicInteger(0)

    /** Effects attached to the capture session; released with it. */
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null

    val minBufferBytes: Int
        get() = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )

    /**
     * Stream microphone chunks until cancelled.
     *
     * Caller must hold RECORD_AUDIO; the flow emits [AudioCaptureError] rather
     * than throwing so Fishing Mode can show a permission message instead of
     * crashing.
     */
    @SuppressLint("MissingPermission")
    fun chunks(): Flow<ByteArray> = callbackFlow {
        val minBuffer = minBufferBytes
        if (minBuffer <= 0) {
            close(AudioCaptureError("This device reported no usable microphone configuration."))
            return@callbackFlow
        }

        // Claim the microphone, then give whoever held it a moment to put it
        // down. Opening a second AudioRecord while the first is still reading
        // is what produced two live captures on the device.
        val generation = captureGeneration.incrementAndGet()
        if (record != null) {
            Log2.w(TAG, "waiting for a previous capture to release the microphone")
            withTimeoutOrNull(STALE_RELEASE_TIMEOUT_MS) {
                while (record != null) delay(STALE_RELEASE_POLL_MS)
            }
        }
        // Over-allocate the device buffer so a scheduling hiccup does not drop
        // samples, while still sending small chunks upstream.
        val bufferBytes = maxOf(minBuffer * 2, chunkBytes * 4)

        val recorder = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .build()
        } catch (e: SecurityException) {
            close(AudioCaptureError("Microphone access is required for Fishing Mode.", e))
            return@callbackFlow
        } catch (e: UnsupportedOperationException) {
            close(AudioCaptureError("This device could not open the microphone.", e))
            return@callbackFlow
        }

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            close(AudioCaptureError("The microphone could not be initialised."))
            return@callbackFlow
        }

        record = recorder
        // The platform's effects, for as long as we still depend on them.
        //
        // They are in the wrong place for what we are trying to do next:
        // [EchoCanceller] works by predicting the microphone from the signal
        // we sent to the speaker, which is only possible while the path
        // between them stays linear.
        // The platform effects are neither linear nor steady: they suppress by
        // an amount that depends on what they think they are hearing, moment
        // to moment. Measured with them attached, the filter that reaches 89 %
        // cancellation against a synthetic room hovered around zero on the
        // device and the alignment it found wandered between 137 and 198 ms
        // for the same unmoved phone. A filter cannot learn a room that is
        // being reshaped underneath it.
        //
        // This also settles an older contradiction: with them attached the
        // leak still measured 4000-7000, so they were never doing the job
        // their presence was justifying.
        attachEffects(recorder.audioSessionId)
        recorder.startRecording()
        if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            recorder.release()
            record = null
            close(AudioCaptureError("The microphone is in use by another app."))
            return@callbackFlow
        }
        Log2.i(TAG, "capture started rate=$sampleRateHz chunk=${chunkBytes}B buffer=${bufferBytes}B")

        val buffer = ByteArray(chunkBytes)
        // Scratch for the canceller: allocated once, because this loop runs
        // fifty times a second for the length of a session.
        var cancelFrames = 0
        val residual = DoubleArray(chunkBytes / 2)
        val residualPcm = ByteArray(chunkBytes)
        try {
            // Three exit conditions, because the first two were not enough.
            //
            // This loop never suspends: `AudioRecord.read` blocks for 20 ms and
            // returns. Cancelling the coroutine around a body that never
            // reaches a suspension point only *marks* it cancelled — nothing
            // interrupts the read, so the loop has to notice by itself. It was
            // testing only `isClosedForSend`, and on the device that stayed
            // false after the collector was cancelled: Fishing Mode stopped,
            // the microphone kept recording, and the next session added a
            // second one (see [captureGeneration]). `isActive` is the job's own
            // state and flips the instant cancel() lands; the generation covers
            // the case where a newer capture starts before this one has been
            // cancelled at all.
            while (isActive && !isClosedForSend && captureGeneration.get() == generation) {
                val read = recorder.read(buffer, 0, buffer.size)
                when {
                    read > 0 -> {
                        // Keep reading even when we publish nothing, so the
                        // AudioRecord buffer never overflows.
                        if (!isMuted) {
                            // Take the assistant's own voice out of the
                            // frame before anything judges it, and before any
                            // of it is sent. What the gate then sees is the
                            // room and the angler, which a level test can
                            // separate -- unlike the raw frame, where the leak
                            // and a voice both measure 3000-7000 on this
                            // handset and no threshold divides them.
                            var judged = buffer
                            var judgedLength = read
                            var judgedReference = speakerLevel()
                            if (CANCELLER_ENABLED && suppressWhileSpeaking) {
                                val samples = canceller.process(
                                    buffer,
                                    read,
                                    playbackFrame(),
                                    residual,
                                )
                                if (++cancelFrames % CANCEL_LOG_EVERY == 0) {
                                    Log2.d(
                                        TAG,
                                        "aec aligned=${canceller.isAligned} " +
                                            "delay=${canceller.alignmentMs}ms " +
                                            "cancelled=${"%.2f".format(canceller.cancelledFraction)} " +
                                            "ref=${canceller.referenceEnergy.toInt()}",
                                    )
                                }
                                if (canceller.isAligned &&
                                    canceller.cancelledFraction >= MIN_CANCELLATION
                                ) {
                                    for (n in 0 until samples) {
                                        val value = residual[n].toInt().coerceIn(-32_768, 32_767)
                                        residualPcm[n * 2] = (value and 0xFF).toByte()
                                        residualPcm[n * 2 + 1] = ((value shr 8) and 0xFF).toByte()
                                    }
                                    judged = residualPcm
                                    judgedLength = samples * 2
                                    // The echo is gone from this frame, so the
                                    // reference has nothing left to explain and
                                    // the gate falls back to the room, which is
                                    // the honest rule once subtraction works.
                                    judgedReference = 0f
                                }
                            }
                            val wasOpen = echoGate.isBargeInOpen
                            val accepted = echoGate.accepts(
                                judged,
                                judgedLength,
                                suppressWhileSpeaking,
                                judgedReference,
                            )
                            if (accepted) {
                                // The transition, not the state: fire once per
                                // turn, on the frame the gate latches open.
                                if (suppressWhileSpeaking && !wasOpen && echoGate.isBargeInOpen) {
                                    runCatching { onBargeIn() }
                                }
                                // Send what was judged: with the echo removed
                                // the server's own VAD stops hearing the
                                // assistant, which is what made it interrupt
                                // itself and language-detect its own output.
                                trySend(judged.copyOf(judgedLength))
                            }
                        }
                    }
                    read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_DEAD_OBJECT -> {
                        Log2.w(TAG, "microphone read failed code=$read")
                        close(AudioCaptureError("The microphone stopped unexpectedly."))
                        return@callbackFlow
                    }
                    else -> Unit // 0 bytes: nothing available yet
                }
            }
        } finally {
            echoGate.reset()
            releaseEffects()
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            record = null
            Log2.i(TAG, "capture stopped")
        }

        awaitClose {
            runCatching { record?.stop() }
            runCatching { record?.release() }
            record = null
        }
    }.flowOn(Dispatchers.IO)

    private fun attachEffects(sessionId: Int) {
        if (!PLATFORM_EFFECTS) {
            Log2.i(TAG, "effects: left off, the canceller owns this signal")
            return
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = runCatching {
                AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = runCatching {
                NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        if (AutomaticGainControl.isAvailable()) {
            gainControl = runCatching {
                AutomaticGainControl.create(sessionId)?.apply { enabled = true }
            }.getOrNull()
        }
        Log2.i(
            TAG,
            "effects: aec=${echoCanceler?.enabled == true} " +
                "ns=${noiseSuppressor?.enabled == true} " +
                "agc=${gainControl?.enabled == true}",
        )
    }

    private fun releaseEffects() {
        runCatching { echoCanceler?.release() }
        runCatching { noiseSuppressor?.release() }
        runCatching { gainControl?.release() }
        echoCanceler = null
        noiseSuppressor = null
        gainControl = null
    }

    companion object {
        /**
         * How much of the frame the canceller has to be removing before its
         * output is trusted in place of the microphone's.
         *
         * Below this the filter has not converged -- the opening moments of a
         * session, or a phone just picked up and moved -- and the residual
         * still carries enough of the assistant to be mistaken for a voice, so
         * the old reference-based rule stays in charge until it has.
         */
        /**
         * Whether to hand the microphone to the platform's effects first.
         *
         * On, for now. They are the wrong thing to have in front of an
         * adaptive canceller -- see [chunks] -- but ours does not yet converge
         * on this handset, so turning them off would remove what little
         * cancellation there is and leave nothing in its place.
         */
        private const val PLATFORM_EFFECTS = true

        /**
         * Whether the adaptive canceller runs at all.
         *
         * Off until it converges on hardware. It reaches 89 % cancellation
         * against a synthetic room in [com.fishmate.ai.EchoCancellerTest] and
         * hovers around zero on a Galaxy A35, where the path it is trying to
         * learn is neither linear nor steady -- the platform effects in front
         * of it reshape the signal, and the playback head it aligns against
         * jitters by tens of milliseconds. Left wired up and tested rather
         * than deleted, because the measurement it rests on is sound and the
         * next step is a proven canceller in place of this one, not a
         * different threshold.
         */
        private const val CANCELLER_ENABLED = false

        private const val MIN_CANCELLATION = 0.75

        /** One line a second while the assistant speaks. */
        private const val CANCEL_LOG_EVERY = 50

        private const val TAG = "AudioCapture"

        /**
         * How long a new capture waits for the previous one to release the
         * microphone. A loop that has been told to stand down exits within one
         * read, so this is generous; waiting forever would hold up a session
         * for a recorder that will never come back.
         */
        private const val STALE_RELEASE_TIMEOUT_MS = 500L
        private const val STALE_RELEASE_POLL_MS = 10L
    }
}

class AudioCaptureError(
    val userMessage: String,
    cause: Throwable? = null,
) : Exception(userMessage, cause)
