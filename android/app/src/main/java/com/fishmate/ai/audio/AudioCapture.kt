package com.fishmate.ai.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.fishmate.ai.core.Log2
import kotlin.math.abs
import kotlin.math.sqrt
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
    private val farEnd = FarEndRing()

    @Volatile
    private var canceller: SpeexEchoCanceller? = null

    /** Where playback has reached, for aligning [canceller]. */
    @Volatile
    var playbackFrame: () -> Long = { 0L }

    /** Far-end audio, as the player commits it to the speaker. */
    fun pushFarEnd(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        farEnd.push(pcm, offset, length, startFrame)
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
        canceller = SpeexEchoCanceller.create(chunkBytes / 2, sampleRateHz)
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
        var farCursor = -1L
        var removedAverage = -1.0
        val micFrame = ShortArray(chunkBytes / 2)
        val farFrame = ShortArray(chunkBytes / 2)
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
                            val aec = canceller
                            if (aec != null && suppressWhileSpeaking) {
                                val samples = minOf(read / 2, aec.frameSamples)
                                var micEnergy = 0.0
                                for (n in 0 until samples) {
                                    val value = ((buffer[n * 2 + 1].toInt() shl 8) or
                                        (buffer[n * 2].toInt() and 0xFF)).toShort()
                                    micFrame[n] = value
                                    micEnergy += value.toDouble() * value
                                }
                                // A continuous far-end stream, not a fresh
                                // lookup each frame.
                                //
                                // The canceller keeps its own history across
                                // calls and models the microphone as a filtered
                                // version of it, so what it is given has to be
                                // the signal as it was actually played: one
                                // frame after another, no gaps and no repeats.
                                // Reading at the playback head instead made the
                                // far end jump by whatever the head happened to
                                // be doing -- tens of milliseconds either way --
                                // and the canceller was handed a stream nothing
                                // had ever played. It removed 72 % of one frame
                                // and added four times the next.
                                val target =
                                    farEnd.cursorFor(playbackFrame()) - samples - CAPTURE_LEAD
                                if (farCursor < 0 || abs(farCursor - target) > RESYNC_SAMPLES) {
                                    farCursor = target
                                }
                                farEnd.copyFrom(farCursor, farFrame)
                                farCursor += samples
                                val cleaned = aec.process(micFrame, farFrame)
                                var cleanEnergy = 0.0
                                for (n in 0 until samples) {
                                    val value = cleaned[n]
                                    cleanEnergy += value.toDouble() * value
                                    residualPcm[n * 2] = (value.toInt() and 0xFF).toByte()
                                    residualPcm[n * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
                                }
                                val removed =
                                    if (micEnergy <= 0.0) 0.0
                                    else 1.0 - sqrt(cleanEnergy / micEnergy)
                                if (++cancelFrames % CANCEL_LOG_EVERY == 0) {
                                    Log2.d(
                                        TAG,
                                        "aec removed=${"%.2f".format(removed)} " +
                                            "mic=${sqrt(micEnergy / samples).toInt()} " +
                                            "clean=${sqrt(cleanEnergy / samples).toInt()} " +
                                            "far=${farRms(farFrame, samples)} " +
                                            "cursor=$farCursor filled=${farEnd.filled}",
                                    )
                                }
                                removedAverage =
                                    if (removedAverage < 0) removed
                                    else removedAverage * (1 - REMOVED_SMOOTHING) +
                                        removed * REMOVED_SMOOTHING
                                if (removedAverage >= TRUSTED_REMOVAL) {
                                    // The echo is out of this frame, so the
                                    // reference has nothing left to explain and
                                    // the gate falls back to the room -- the
                                    // honest rule once subtraction works, and
                                    // the one thing a level test can do well.
                                    judged = residualPcm
                                    judgedLength = samples * 2
                                    judgedReference = 0f
                                }
                                // Below that, the raw frame and the old
                                // reference-based bar stay in charge. A
                                // half-cancelled frame judged against the room
                                // floor is worse than no cancellation at all:
                                // what is left of the assistant still clears a
                                // bar meant for a quiet room, and the
                                // assistant interrupts itself.
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
            canceller?.release()
            canceller = null
            farEnd.reset()
        }
    }.flowOn(Dispatchers.IO)

    private fun farRms(frame: ShortArray, samples: Int): Int {
        var sum = 0.0
        for (n in 0 until samples) sum += frame[n].toDouble() * frame[n]
        return sqrt(sum / samples).toInt()
    }

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
        private const val PLATFORM_EFFECTS = false



        /** One line a second while the assistant speaks. */
        private const val CANCEL_LOG_EVERY = 50

        /**
         * How far the far-end cursor may drift from the playback head before
         * it is put back: 100 ms. Small enough that a flush or a long stall is
         * followed, large enough that ordinary jitter in the head reading --
         * which is what broke the first attempt -- never moves it.
         */
        private const val RESYNC_SAMPLES = 1_600

        /**
         * How far back from the playback head the far end is read: 150 ms.
         *
         * The reference has to *lead* the microphone -- a canceller models the
         * echo as the far end filtered and delayed, and it has no way to
         * represent a reference that arrives after the sound it is supposed to
         * explain. The recorder hands over a buffer that has been filling for
         * up to 80 ms, so a frame read now contains audio from before the head
         * reading taken beside it, and feeding the far end at the head put the
         * reference behind the microphone. This puts it comfortably in front,
         * and the filter spans the rest.
         */
        private const val CAPTURE_LEAD = 2_400

        /**
         * How much of the frame the canceller has to be removing, averaged,
         * before its output is judged in place of the microphone's.
         *
         * Measured on a Galaxy A35 at 0.25-0.77 frame to frame, which is real
         * cancellation and not yet enough to trust blind: see the notes in
         * [SpeexEchoCanceller]. Until the average clears this the frame is
         * judged exactly as it was before any of this existed.
         */
        private const val TRUSTED_REMOVAL = 0.6

        private const val REMOVED_SMOOTHING = 0.05

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
