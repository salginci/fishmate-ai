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
        // VOICE_COMMUNICATION is supposed to engage these, but on many devices
        // they are not attached unless asked for explicitly. Without echo
        // cancellation the loop described on `suppressWhileSpeaking` is
        // unavoidable.
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
                            val wasOpen = echoGate.isBargeInOpen
                            val accepted = echoGate.accepts(
                                buffer,
                                read,
                                suppressWhileSpeaking,
                                speakerLevel(),
                            )
                            if (accepted) {
                                // The transition, not the state: fire once per
                                // turn, on the frame the gate latches open.
                                if (suppressWhileSpeaking && !wasOpen && echoGate.isBargeInOpen) {
                                    runCatching { onBargeIn() }
                                }
                                trySend(buffer.copyOf(read))
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
