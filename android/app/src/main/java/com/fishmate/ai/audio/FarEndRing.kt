package com.fishmate.ai.audio

/**
 * What we last sent to the speaker, at the microphone's rate, addressable by
 * playback position.
 *
 * The echo canceller needs the far-end signal lined up with the frame it is
 * cancelling, and nothing else in the audio stack can provide it: the player
 * runs at 24 kHz because that is what the Live API sends, the recorder at
 * 16 kHz because that is what it accepts, and the two are different clocks on
 * different threads. This keeps a second of far-end audio resampled to the
 * microphone's rate and indexed by the playback frame it belongs to, so a
 * captured frame can ask what was coming out of the speaker while it was
 * being filled.
 *
 * Alignment is deliberately coarse. The filter behind it spans
 * [SpeexEchoCanceller.FILTER_MS], far more than the 130-200 ms this device was
 * measured at, so the remaining delay is something the filter absorbs rather
 * than something this has to get right — which matters, because estimating it
 * per frame is exactly what broke the attempt before this one.
 */
class FarEndRing(
    private val outputRateHz: Int = 24_000,
    private val captureRateHz: Int = 16_000,
    seconds: Int = 1,
) {
    private val capacity = captureRateHz * seconds
    private val ring = ShortArray(capacity)

    @Volatile
    private var end = 0L

    @Volatile
    private var start = 0L

    /**
     * File far-end audio as it is committed to the speaker. [pcm] is 16-bit
     * little-endian mono at [outputRateHz]; [startFrame] is its position in
     * output frames, the counter `AudioTrack.getPlaybackHeadPosition` reports.
     */
    fun push(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        var index = toCaptureRate(startFrame)
        // Linear interpolation rather than dropping samples: 24 kHz to 16 kHz
        // is two out of every three, and decimating without a filter aliases
        // into the speech band, where the whole decision is made.
        val step = outputRateHz.toDouble() / captureRateHz
        var position = 0.0
        val samples = length / 2
        while (true) {
            val at = position.toInt()
            if (at >= samples) break
            val a = sampleAt(pcm, offset + at * 2)
            val b = if (at + 1 < samples) sampleAt(pcm, offset + (at + 1) * 2) else a
            val fraction = position - at
            put(index, (a + (b - a) * fraction).toInt().toShort())
            index++
            position += step
        }
    }

    /**
     * Fill [out] with [out].size samples starting at [from], in capture-rate
     * samples. Samples we do not have read as silence, which is what a starved
     * or not-yet-started stream actually sounds like.
     */
    fun copyFrom(from: Long, out: ShortArray) {
        for (n in out.indices) {
            out[n] = at(from + n)
        }
    }

    /** Where a playback head reading lands in capture-rate samples. */
    fun cursorFor(headFrame: Long): Long = toCaptureRate(headFrame)

    fun reset() {
        end = 0L
        start = 0L
        ring.fill(0)
    }

    internal fun at(index: Long): Short =
        if (index < start || index >= end) 0 else ring[(index % capacity).toInt()]

    internal val filled: Long get() = end

    private fun toCaptureRate(outputFrame: Long): Long =
        outputFrame * captureRateHz / outputRateHz

    private fun sampleAt(pcm: ByteArray, i: Int): Double =
        (((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()).toDouble()

    private fun put(index: Long, value: Short) {
        if (index < 0) return
        ring[(index % capacity).toInt()] = value
        end = index + 1
        start = maxOf(0L, end - capacity)
    }
}
