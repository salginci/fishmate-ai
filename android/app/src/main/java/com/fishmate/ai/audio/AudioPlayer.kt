package com.fishmate.ai.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.fishmate.ai.core.Log2
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Plays the model's native audio: 16-bit PCM at 24 kHz (the Live API output
 * rate, which differs from the 16 kHz input rate).
 *
 * The queue plus [flush] is what implements barge-in. When the server reports
 * an interruption, every already-buffered chunk must be discarded *and* the
 * AudioTrack flushed — otherwise the model keeps talking for a second or two
 * after the angler cut in, which feels broken.
 */
class AudioPlayer(
    private val sampleRateHz: Int = 24_000,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
) {
    private val queue = ConcurrentLinkedQueue<ByteArray>()

    @Volatile
    private var track: AudioTrack? = null

    /**
     * Incremented by [flush] and [stop]; identifies the current response.
     *
     * Volatile because the pump reads it on its own thread between blocking
     * writes while a barge-in increments it from the event loop.
     */
    @Volatile
    private var playbackEpoch: Int = 0

    private var lastLoggedSecond = -1L

    @Volatile
    private var pumpJob: Job? = null

    private val meter = OutputLevelMeter(sampleRateHz)

    /**
     * Frames handed to the device since playback last started or flushed.
     *
     * Kept in lockstep with `AudioTrack.getPlaybackHeadPosition()`, which also
     * restarts at zero on `flush()` and `stop()`.
     */
    @Volatile
    private var framesWritten = 0L

    private val _playing = MutableStateFlow(false)

    /**
     * Whether audio is currently going out of the speaker.
     *
     * Observable because the capture side gates the microphone on it to break
     * the echo loop; see `AudioCapture.suppressWhileSpeaking`.
     */
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    var isPlaying: Boolean
        get() = _playing.value
        private set(value) { _playing.value = value }

    /** Bytes still waiting to be written; drives the "speaking" UI state. */
    val queuedBytes: Int get() = queue.sumOf { it.size }

    /**
     * Loudness of the model's voice leaving the speaker right now, 0 to 1.
     *
     * Read once per animation frame by the avatar, so it is a plain getter
     * rather than a flow: sixty state emissions a second would recompose the
     * whole screen to move one mouth.
     *
     * This is a measurement, not a playback state — it keeps reporting the
     * tail still inside the device buffer after our queue drains. Callers gate
     * it on the assistant actually speaking.
     */
    val outputLevel: Float
        get() {
            val current = track ?: return 0f
            // The head position is an unsigned frame counter in an Int.
            val head = current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            return meter.levelAt(head)
        }

    /**
     * What the room is hearing, for the echo gate -- see
     * [OutputLevelMeter.referenceAt]. Distinct from [outputLevel], which drives
     * the avatar and must stay silent when nothing is scheduled.
     */
    /**
     * Far-end audio as it is committed to the speaker: the buffer, its offset
     * and length, and the playback frame it starts at.
     */
    @Volatile
    var onFarEnd: ((ByteArray, Int, Int, Long) -> Unit)? = null

    /** Where playback has reached, in frames, for aligning the canceller. */
    val playbackFrame: Long
        get() {
            val current = track ?: return 0L
            return current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
        }

    val echoReference: Float
        get() {
            val current = track ?: return 0f
            val head = current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            return meter.referenceAt(head)
        }

    /**
     * True while the speaker is still producing sound.
     *
     * [playing] goes false as soon as our own queue empties, which is a few
     * hundred milliseconds before the device buffer finishes draining. That
     * gap is harmless for the echo gate but visible on the avatar, so this
     * compares the playback head against everything handed to the device.
     */
    val isAudible: Boolean
        get() {
            val current = track ?: return false
            if (queue.isNotEmpty()) return true
            val head = current.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
            return head < framesWritten
        }

    fun start() {
        if (track != null) return
        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            Log2.e(TAG, "no usable AudioTrack configuration at ${sampleRateHz}Hz")
            return
        }
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    // VOICE_COMMUNICATION pairs with the capture side's echo
                    // canceller and routes to a Bluetooth headset correctly.
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            Log2.e(TAG, "AudioTrack failed to initialise")
            return
        }
        track = created
        framesWritten = 0L
        meter.reset()
        created.play()
        startPump()
        Log2.i(TAG, "player started rate=$sampleRateHz")
    }

    private fun startPump() {
        pumpJob?.cancel()
        pumpJob = scope.launch {
            while (isActive) {
                // Read *before* the poll, so a flush landing between taking a
                // chunk and writing it is still caught: the chunk is already
                // out of the queue by then and clearing the queue cannot
                // reach it.
                val epoch = playbackEpoch
                val chunk = queue.poll()
                if (chunk == null) {
                    if (isPlaying) isPlaying = false
                    delay(IDLE_POLL_MS)
                    continue
                }
                if (epoch != playbackEpoch) continue
                isPlaying = true
                // The reference the echo gate depends on, measured at source.
                // It reported silence while this pump was actively writing,
                // and nothing downstream can be judged until we know why.
                // Triggered on seconds of audio written, not on chunks: the
                // service sends a turn as a handful of large blobs, so a
                // per-chunk counter fired either never or once.
                val second = framesWritten / sampleRateHz
                if (second != lastLoggedSecond) {
                    lastLoggedSecond = second
                    val head = track?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL) ?: -1
                    Log2.d(
                        TAG,
                        "pump written=$framesWritten head=$head " +
                            "level=${"%.2f".format(outputLevel)} queued=$queuedBytes",
                    )
                }
                val current = track ?: break
                var offset = 0
                while (offset < chunk.size && isActive) {
                    // Blocking write applies natural backpressure: we only
                    // return when the device has consumed the samples.
                    // Filed *before* the write, not after.
                    //
                    // `write` blocks until the device has taken the samples,
                    // so anything filed after it returns describes audio that
                    // has already left the speaker. Measured: the far-end ring
                    // ran 120 ms behind the playback head, so the canceller
                    // was handed silence for exactly the frames carrying the
                    // echo -- `far=0` against a microphone reading 2873. The
                    // level meter had the same lateness, which is what made it
                    // report a silent speaker over a loud room.
                    //
                    // The cost of filing early is a flush: samples filed here
                    // that never reach the room. That is rare, bounded by one
                    // chunk, and self-correcting, where being late is neither.
                    val pending = chunk.size - offset
                    meter.record(chunk, offset, pending, framesWritten)
                    onFarEnd?.invoke(chunk, offset, pending, framesWritten)
                    val written = runCatching {
                        current.write(chunk, offset, chunk.size - offset)
                    }.getOrDefault(-1)
                    if (written <= 0) break
                    // Checked *after* the blocking write, which is the whole
                    // point: clearing the queue stops future chunks, but the
                    // one already being written kept going to the speaker for
                    // its full length after the angler had cut in, and its
                    // frames were filed against a counter flush() had just
                    // reset — so the avatar's mouth moved to audio nobody
                    // should have been hearing.
                    if (epoch != playbackEpoch) break
                    framesWritten += written / BYTES_PER_FRAME
                    offset += written
                }
            }
        }
    }

    fun enqueue(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        if (track == null) start()
        // Bound the queue: on a bad connection a burst could otherwise grow
        // without limit and play minutes-old speech.
        if (queuedBytes > MAX_QUEUED_BYTES) {
            // Error, not warning: this is the assistant's speech being
            // cut, which the angler hears as a sentence losing its middle.
            Log2.e(TAG, "playback queue over ${MAX_QUEUED_BYTES}B, dropping oldest")
            while (queuedBytes > MAX_QUEUED_BYTES / 2) queue.poll() ?: break
        }
        queue.add(pcm)
    }

    /**
     * Discard pending audio immediately (barge-in, or leaving Fishing Mode).
     *
     * Both halves are required: clearing our queue stops *future* writes, and
     * `AudioTrack.flush()` drops what the hardware already has.
     */
    fun flush() {
        // Bumped before anything else: the pump may be blocked inside write()
        // right now, and this is what tells it to drop the rest of the chunk.
        playbackEpoch++
        queue.clear()
        val current = track ?: return
        runCatching {
            current.pause()
            current.flush()
            current.play()
        }.onFailure { Log2.w(TAG, "flush failed", it) }
        framesWritten = 0L
        meter.reset()
        isPlaying = false
        Log2.d(TAG, "playback flushed (interruption)")
    }

    fun stop() {
        playbackEpoch++
        pumpJob?.cancel()
        pumpJob = null
        queue.clear()
        framesWritten = 0L
        meter.reset()
        isPlaying = false
        val current = track ?: return
        runCatching {
            current.pause()
            current.flush()
            current.stop()
            current.release()
        }
        track = null
        Log2.i(TAG, "player stopped")
    }

    companion object {
        private const val TAG = "AudioPlayer"
        private const val IDLE_POLL_MS = 10L

        /**
         * How far the output drops while the gate checks whether someone is
         * talking. About 16 dB down: enough that a leak which reads 3000 at
         * the microphone reads a few hundred, and far short of silence, so a
         * false alarm is heard as a dip rather than as a gap.
         */


        /** 16-bit mono. */
        private const val BYTES_PER_FRAME = 2

        /**
         * ~60 s of 24 kHz 16-bit mono.
         *
         * This was 8 s, which sounds generous until you measure what one turn
         * actually delivers: the server generates faster than real time and
         * sent 16.5 s of speech in 5 s for a three-sentence answer. The cap
         * was reached mid-sentence and the oldest chunks were dropped, so the
         * angler simply never heard part of the reply — on the device, once,
         * during a single test session.
         *
         * The cap is a guard against unbounded growth on a bad connection,
         * not a buffer policy: barge-in is handled by [flush], so nothing
         * legitimate should ever reach this.
         */
        private const val MAX_QUEUED_BYTES = 24_000 * 2 * 60
    }
}
