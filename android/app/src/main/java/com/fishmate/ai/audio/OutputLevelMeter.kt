package com.fishmate.ai.audio

import kotlin.math.sqrt

/**
 * How loud the model's voice is *at the instant it reaches the speaker*.
 *
 * The avatar's mouth is driven by this rather than by the `speaking` flag, so
 * it articulates syllables instead of hanging open for a whole turn.
 *
 * The obvious implementation — take the RMS of each chunk as it is handed to
 * [AudioPlayer] — is wrong by the length of the device buffer. We write ahead
 * of what is audible, so the mouth would move before the sound arrives. So
 * every window is filed under the frame position at which it will *play*, and
 * read back against `AudioTrack.getPlaybackHeadPosition()`.
 *
 * Frame-indexed and free of Android types on purpose: the behaviour is pinned
 * by `OutputLevelMeterTest` without an audio device.
 */
class OutputLevelMeter(
    sampleRateHz: Int = 24_000,
    /** Analysis window. 20 ms matches the Live API's own frame size. */
    windowMs: Int = 20,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val windowFrames = (sampleRateHz * windowMs / 1000).coerceAtLeast(1)

    // Three parallel arrays rather than objects: this is written from the
    // playback pump and read every animation frame.
    private val levels = FloatArray(capacity)
    private val startFrames = LongArray(capacity)
    private val frameCounts = IntArray(capacity)

    @Volatile
    private var writeIndex = 0

    init {
        reset()
    }

    /**
     * Forget everything measured so far.
     *
     * Must be called wherever `AudioTrack`'s playback head restarts at zero —
     * `flush()` and `stop()` — or stale windows would be read back against
     * fresh frame positions.
     */
    fun reset() {
        startFrames.fill(NO_FRAME)
        frameCounts.fill(0)
        levels.fill(0f)
        writeIndex = 0
    }

    /**
     * File [length] bytes of 16-bit little-endian mono PCM starting at [offset],
     * which will begin playing at [startFrame] frames after playback started.
     */
    fun record(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        var at = offset
        val end = offset + length
        var frame = startFrame
        while (at + 1 < end) {
            val samples = minOf(windowFrames, (end - at) / 2)
            if (samples == 0) break
            var sum = 0.0
            var i = at
            val windowEnd = at + samples * 2
            while (i + 1 < windowEnd) {
                val sample = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort().toInt()
                sum += sample.toDouble() * sample.toDouble()
                i += 2
            }
            val rms = sqrt(sum / samples)
            put(frame, samples, (rms / FULL_SCALE * SPEECH_GAIN).toFloat().coerceIn(0f, 1f))
            frame += samples
            at = windowEnd
        }
    }

    private fun put(startFrame: Long, frames: Int, level: Float) {
        val slot = writeIndex
        startFrames[slot] = startFrame
        frameCounts[slot] = frames
        levels[slot] = level
        writeIndex = (slot + 1) % levels.size
    }

    /**
     * Loudness of the frame currently leaving the speaker, 0 to 1.
     *
     * Returns 0 for a frame position nothing was recorded for, which covers
     * both "not speaking yet" and "the ring has already wrapped past it".
     */
    fun levelAt(playbackFrame: Long): Float {
        for (slot in levels.indices) {
            val start = startFrames[slot]
            if (start == NO_FRAME) continue
            if (playbackFrame >= start && playbackFrame < start + frameCounts[slot]) {
                return levels[slot]
            }
        }
        return 0f
    }

    companion object {
        /** ~5 s of 20 ms windows: longer than any device buffer plus queue. */
        private const val DEFAULT_CAPACITY = 256

        private const val FULL_SCALE = 32_768.0

        /**
         * Speech rarely exceeds about 0.3 of full scale RMS, so lift it into a
         * usable 0..1 range. Matches the web client's analyser gain, so the two
         * avatars move the same way.
         */
        private const val SPEECH_GAIN = 3.2

        private const val NO_FRAME = Long.MIN_VALUE
    }
}
