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

    /** Bumped on [resetEcho], so the capture loop drops its cursor. */
    private val echoReset = AtomicInteger(0)

    /** Where playback has reached, for aligning [canceller]. */
    @Volatile
    var playbackFrame: () -> Long = { 0L }

    /** Far-end audio, as the player commits it to the speaker. */
    fun pushFarEnd(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        farEnd.push(pcm, offset, length, startFrame)
    }

    /**
     * Playback restarted from zero: drop everything that was measured against
     * the old playback clock.
     *
     * Without this an interruption feeds the next one. The server cancels the
     * turn, playback is flushed, `framesWritten` and the playback head both
     * return to zero, and the far-end ring is suddenly describing a timeline
     * that no longer exists -- so the next sentence is cancelled against
     * nothing, its echo reaches the server unattenuated, and it is interrupted
     * too. Measured on the device as three interruptions in five seconds with
     * the microphone reading 1 and 2.
     */
    fun resetEcho() {
        // The ring and the cursor go, because they are counted against a
        // playback clock that has just restarted. The filter stays.
        //
        // Dropping its weights too looked right and made things worse: they
        // describe the room -- speaker to microphone -- which a flush does not
        // change, and starting cold after every interruption meant the filter
        // never reconverged between them. Measured as cancellation falling to
        // 0.04 and -0.03 in a stretch where interruptions arrived every two to
        // three seconds, each one feeding the next.
        farEnd.reset()
        echoReset.incrementAndGet()
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
        var farCursor = 0L
        var anchored = false
        var lastFilled = 0L
        var calibrations = 0
        var lastPeak = -1
        var steadyPeaks = 0
        var seenReset = echoReset.get()
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
                            var referenced = false
                            val aec = canceller
                            // Only while the assistant is speaking.
                            //
                            // Running it on every frame is the textbook
                            // arrangement and it does not survive this audio
                            // stack: the far end is indexed by playback
                            // position, which stops while the server is
                            // between blobs, and the microphone is not, so the
                            // cursor walks off the end of the ring and stays
                            // there -- measured at 770984 against a ring
                            // filled to 42240, a canceller fed silence for the
                            // rest of the session. Feeding it only through a
                            // turn costs convergence between turns and keeps
                            // the two timelines in contact.
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
                                if (seenReset != echoReset.get()) {
                                    seenReset = echoReset.get()
                                    anchored = false
                                }
                                // Anchored once, then advanced by the frame.
                                //
                                // Both streams are driven by the same audio
                                // clock -- 320 captured samples is 480 played
                                // frames, every 20 ms -- so a cursor that just
                                // steps keeps the alignment the filter learned.
                                // Re-deriving it from the playback head each
                                // frame, or snapping to it on drift, moves that
                                // alignment by whatever the head happens to be
                                // doing, and the filter's own impulse response
                                // showed the cost: its peak wandered between
                                // tap 1 and tap 110 instead of sitting still.
                                //
                                // A stall needs no special case. The ring stops
                                // filling, the cursor walks past it, `holds`
                                // goes false and the frames read as the silence
                                // the speaker was actually producing. The one
                                // real discontinuity is a flush, which resets
                                // the playback clock and is announced.
                                // Playback stalls whenever the server is
                                // between blobs, and the far-end timeline
                                // stalls with it while the microphone keeps
                                // running on wall clock. Left alone the cursor
                                // walks off the end of the ring and never
                                // comes back -- measured as a cursor at 908000
                                // against a ring filled to 480320, which is a
                                // canceller fed silence for the rest of the
                                // session. So when audio starts arriving again
                                // and the cursor is past it, take the anchor
                                // afresh.
                                val filledNow = farEnd.filled
                                if (anchored && farCursor > filledNow && filledNow > lastFilled) {
                                    anchored = false
                                }
                                lastFilled = filledNow
                                if (!anchored) {
                                    // A flag, not a negative cursor: the
                                    // anchor is taken a third of a second
                                    // before the playback head, which at the
                                    // start of a turn is a negative position,
                                    // and treating that as "not anchored yet"
                                    // re-anchored on every frame.
                                    anchored = true
                                    calibrations = 0
                                    lastPeak = -1
                                    steadyPeaks = 0
                                    farCursor = farEnd.cursorFor(playbackFrame()) -
                                        samples - CAPTURE_LEAD
                                    Log2.i(TAG, "aec anchored at $farCursor")
                                }
                                referenced = farEnd.holds(farCursor, samples)
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
                                // Self-calibration: move the cursor so the
                                // room sits at the front of the filter.
                                //
                                // The distance from the playback head to the
                                // microphone is not a constant -- measured at
                                // 371 ms in one session and 427 ms in the next,
                                // because it depends on how full the output
                                // buffer was when the track started -- so no
                                // written-down lead can be right. The filter
                                // knows, though: the peak of its impulse
                                // response is exactly how far past our
                                // alignment the echo sits. Shift the cursor by
                                // that much and the whole filter is available
                                // for the room instead of for delay. The
                                // weights describe the old alignment, so they
                                // go with it; this happens a handful of times
                                // at the start of a session and then stops.
                                if (++cancelFrames % CANCEL_LOG_EVERY == 0) {
                                    val peak = aec.peakTap()
                                    // Only a *settled* peak is worth acting
                                    // on. A filter that was just reset has an
                                    // impulse response made of noise, and
                                    // realigning on that is how the first
                                    // version of this hunted: -297, +270, -262,
                                    // -314 samples, never arriving. Left alone
                                    // the filter does settle -- the same
                                    // session showed the peak sitting still at
                                    // tap 1240 for seconds -- so the test is
                                    // whether it has stopped moving.
                                    val settled = peak > 0 && lastPeak > 0 &&
                                        abs(peak - lastPeak) < PEAK_STABLE_SAMPLES
                                    steadyPeaks = if (settled) steadyPeaks + 1 else 0
                                    lastPeak = peak
                                    if (steadyPeaks >= PEAK_STABLE_CYCLES &&
                                        calibrations < MAX_CALIBRATIONS &&
                                        abs(peak - TARGET_TAP) > TAP_TOLERANCE
                                    ) {
                                        calibrations++
                                        steadyPeaks = 0
                                        lastPeak = -1
                                        farCursor -= (peak - TARGET_TAP)
                                        aec.reset()
                                        removedAverage = -1.0
                                        Log2.i(
                                            TAG,
                                            "aec realigned by ${peak - TARGET_TAP} " +
                                                "samples (peak=$peak)",
                                        )
                                    }
                                }
                                if (cancelFrames % CANCEL_LOG_EVERY == 0) {
                                    Log2.d(
                                        TAG,
                                        "aec removed=${"%.2f".format(removed)} " +
                                            "mic=${sqrt(micEnergy / samples).toInt()} " +
                                            "clean=${sqrt(cleanEnergy / samples).toInt()} " +
                                            "far=${farRms(farFrame, samples)} " +
                                            "peak=${aec.peakTap()} " +
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
                            if (suppressWhileSpeaking && !wasOpen && accepted &&
                                echoGate.isBargeInOpen
                            ) {
                                // The transition, not the state: fire once per
                                // turn, on the frame the gate latches open.
                                // This stops playback locally, a round trip
                                // before the server's own interruption lands.
                                runCatching { onBargeIn() }
                            }

                            // Barge-in is the server's decision, not ours.
                            //
                            // The Live API runs voice activity detection on
                            // what it is sent and cancels the turn the moment
                            // it hears speech -- `activityHandling` is
                            // START_OF_ACTIVITY_INTERRUPTS and the backend sets
                            // it explicitly. None of that can fire on audio it
                            // never receives, and until now the gate withheld
                            // the microphone for the whole of the assistant's
                            // turn: the one component in this system actually
                            // built to recognise speech was never shown any.
                            // We were doing its job locally with a level
                            // threshold, which is what every measurement here
                            // has been failing to make work.
                            //
                            // So the gate no longer decides what the server
                            // hears. It decides when to stop playback early,
                            // which is a latency optimisation, and the echo
                            // canceller is what keeps the assistant's own voice
                            // out of the stream -- the actual fix for the
                            // self-interruption this gate was built to prevent.
                            // Streaming through the turn is only safe while we
                            // have the reference that makes cancellation
                            // possible. Just after a flush the ring is empty
                            // and the playback head has restarted, so the frame
                            // is the assistant's voice with nothing taken out
                            // of it -- send that and the server interrupts the
                            // sentence it has only just started, which flushes
                            // again. The gate's own judgement covers that gap.
                            // Streamed through the turn only while the
                            // residual is clean enough to be worth judging.
                            // Audio we would not trust ourselves is audio the
                            // server should not be asked to judge either: sent
                            // anyway, with cancellation at 0.04, it is the
                            // assistant's own voice and it is interrupted as
                            // such.
                            val trusted = referenced && removedAverage >= TRUSTED_REMOVAL
                            if (accepted || (suppressWhileSpeaking && trusted)) {
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
         * How far back from the playback head the far end is read: 350 ms.
         *
         * The reference has to *lead* the microphone -- a canceller models the
         * echo as the far end filtered and delayed, and it has no way to
         * represent a reference that arrives after the sound it is supposed to
         * explain.
         *
         * The number is measured, not assumed. Once the alignment stopped
         * moving the filter's impulse response settled with its peak at tap
         * 3542, which with the 150 ms lead in use at the time puts the real
         * distance from the playback head to the microphone at about 371 ms --
         * the device's output buffer, the room, and a recorder that hands over
         * a buffer which has been filling for up to 80 ms. At 150 ms the
         * filter was spending three quarters of its 300 ms span on pure delay
         * and had 80 ms left for the room. This leaves the peak near tap 340
         * and the rest of the filter for what it is for.
         */
        private const val CAPTURE_LEAD = 5_600

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

        /** Where the room should sit in the filter: 20 ms in. */
        private const val TARGET_TAP = 320

        /** How far off that is worth a realignment. */
        private const val TAP_TOLERANCE = 240

        /** Realignments per anchor, so this settles instead of hunting. */
        private const val MAX_CALIBRATIONS = 2

        /** How still the peak has to be, and for how long, to be believed. */
        private const val PEAK_STABLE_SAMPLES = 160
        private const val PEAK_STABLE_CYCLES = 3

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
