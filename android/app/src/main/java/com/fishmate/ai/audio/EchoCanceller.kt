package com.fishmate.ai.audio

import kotlin.math.sqrt

/**
 * Subtracts the assistant's own voice from the microphone, using the signal we
 * sent to the speaker.
 *
 * ## Why this exists
 *
 * Every rule that came before it tried to tell the leak from a voice by how
 * *loud* it was, and the handset says that cannot be done: measured on a
 * Galaxy A35, the assistant's leak arrives at the microphone at 3177-6939 of
 * speech-band RMS and so does an angler talking across the desk. Three
 * thresholds were fitted to that overlap and each one either cut the greeting
 * off or swallowed a question, and ducking the speaker to break the tie was
 * audible — not something a product about the Live API can do to its own
 * voice. The ratio between our reference level and the leak it predicts moves
 * by a factor of three from frame to frame, because a 20 ms power reading of
 * what we *wrote* is a crude model of what a room *returns*.
 *
 * Loudness is the wrong question. What the microphone carries during an echo
 * is not merely as loud as the far end — it is a delayed, filtered **copy** of
 * it, and we are holding the original. So this estimates that filter and
 * subtracts it. What survives is either silence, the room, or the angler, and
 * telling those three apart is a job a level test can actually do.
 *
 * ## How
 *
 * Normalised least mean squares, the standard adaptive echo canceller, at
 * [TAPS] taps over 16 kHz samples — 16 ms, which covers the direct path across
 * a desk and the first reflections. Anything longer than the filter is handled
 * by [delaySamples], a coarse alignment measured once per turn, so the taps
 * are spent on the room's response rather than on the device's output buffer.
 *
 * Adaptation is frozen when the residual is a large fraction of the input:
 * that is the signature of someone talking over the assistant, and a filter
 * that keeps adapting through double talk learns to cancel the person it
 * exists to hear.
 */
class EchoCanceller(
    private val taps: Int = TAPS,
    private val stepSize: Double = STEP_SIZE,
) {
    private val weights = DoubleArray(taps)

    /** Far-end samples at 16 kHz, indexed by playback position modulo the ring. */
    private val ring = ShortArray(RING_SAMPLES)

    /** One past the newest far-end sample filed, in 16 kHz playback samples. */
    @Volatile
    private var ringEnd = 0L

    /** Oldest sample still in the ring. */
    @Volatile
    private var ringStart = 0L

    /** Alignment between the playback head and what the microphone is hearing. */
    private var delaySamples = -1

    /** Smoothed echo return loss, 0..1, the trust measure for the residual. */
    private var erle = 0.0

    private var poorFrames = 0
    private var divergedFrames = 0

    /** Energy of the far-end window the filter is currently looking at. */
    private var windowPower = 0.0

    /** Energy of the far end seen in the last processed frame, for callers. */
    var referenceEnergy: Double = 0.0
        private set

    /** How much of the input the filter managed to explain, 0..1. */
    var cancelledFraction: Double = 0.0
        private set

    val isAligned: Boolean get() = delaySamples >= 0

    /** One past the newest far-end sample filed, in 16 kHz samples. */
    internal val farEndEnd: Long get() = ringEnd

    val alignmentMs: Int get() = if (delaySamples < 0) -1 else delaySamples * 1000 / RATE

    /**
     * File far-end audio as it is handed to the speaker.
     *
     * [pcm] is 24 kHz mono 16-bit little-endian — what the Live API sends —
     * and is decimated to 16 kHz here so the two streams can be compared
     * sample for sample. [startFrame] is its position in 24 kHz playback
     * frames, the same counter `AudioTrack.getPlaybackHeadPosition` reports.
     */
    fun pushFarEnd(pcm: ByteArray, offset: Int, length: Int, startFrame: Long) {
        var i = offset
        val end = offset + length
        // 24 kHz to 16 kHz is two samples out of every three. Linear
        // interpolation rather than decimation: dropping samples aliases, and
        // the alias lands in the speech band where the whole decision is made.
        var outIndex = startFrame * OUT_PER_IN_NUM / OUT_PER_IN_DEN
        var position = (startFrame * OUT_PER_IN_NUM % OUT_PER_IN_DEN).toDouble() / OUT_PER_IN_NUM
        while (i + 1 < end) {
            val a = sampleAt(pcm, i)
            val b = if (i + 3 < end) sampleAt(pcm, i + 2) else a
            while (position < 1.0) {
                put(outIndex, (a + (b - a) * position).toInt().toShort())
                outIndex++
                position += IN_PER_OUT
            }
            position -= 1.0
            i += 2
        }
    }

    private fun sampleAt(pcm: ByteArray, i: Int): Double =
        (((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()).toDouble()

    private fun put(index: Long, value: Short) {
        ring[(index % RING_SAMPLES).toInt()] = value
        ringEnd = index + 1
        ringStart = maxOf(0L, ringEnd - RING_SAMPLES)
    }

    internal fun farEndAt(index: Long): Double =
        if (index < ringStart || index >= ringEnd) 0.0
        else ring[(index % RING_SAMPLES).toInt()].toDouble()

    /**
     * Remove the echo from one captured frame.
     *
     * [mic] is 16 kHz mono 16-bit little-endian. [headFrame] is the playback
     * head in 24 kHz frames, read as close to the capture as the caller can
     * manage. Returns the residual: the same frame with everything the far end
     * can account for taken out of it, which the caller then judges.
     */
    fun process(mic: ByteArray, length: Int, headFrame: Long, residual: DoubleArray): Int {
        val samples = minOf(length / 2, residual.size)
        if (samples == 0) return 0
        val head16 = headFrame * OUT_PER_IN_NUM / OUT_PER_IN_DEN

        var micEnergy = 0.0
        for (n in 0 until samples) {
            residual[n] = ((mic[n * 2 + 1].toInt() shl 8) or (mic[n * 2].toInt() and 0xFF))
                .toShort().toDouble()
            micEnergy += residual[n] * residual[n]
        }

        // The alignment is measured once and then left alone.
        //
        // It was re-measured every second at first, and the device showed why
        // that cannot work: successive estimates of the same path came back at
        // 120, 151, 143, 145, 131, 147, 126 and 166 ms, and every move
        // invalidates every weight the filter has learned, because a weight
        // means "this much of the far end, this many samples ago". The filter
        // spent the turn re-converging and cancelled a third of the echo
        // instead of nearly all of it. So a new estimate is only accepted once
        // cancellation has been poor for long enough to mean the path really
        // did change -- the phone picked up, or playback flushed -- and the
        // weights go with it, since they describe the old alignment.
        if (delaySamples < 0 || poorFrames >= REALIGN_AFTER_POOR_FRAMES) {
            val found = estimateDelay(residual, samples, head16)
            if (found >= 0 && found != delaySamples) {
                delaySamples = found
                weights.fill(0.0)
                erle = 0.0
            }
            poorFrames = 0
            if (delaySamples < 0) {
                referenceEnergy = 0.0
                return samples
            }
        }

        // A little ahead of the alignment, so the filter spans it rather than
        // starting at it, and long enough that the alignment never has to move
        // again.
        //
        // Two shorter-filter attempts failed here and both failed the same
        // way. The playback head is read on the capture thread, not when the
        // frame was captured, so the apparent delay jitters by tens of
        // milliseconds; searching for it per frame looked like the answer and
        // was worse, because on a frame with little in it the search locks
        // onto whatever correlates -- a late reflection, or noise -- and
        // weights mean nothing except at the alignment they were learned at.
        // The tail of every gap then came out twice as loud as it went in.
        // Jitter is cheaper to absorb than to track: a filter long enough to
        // span it sees one consistent room and converges.
        //
        // The first version led by half the filter. On a stream that is
        // repeatedly starved, the head catches up with the last frame written
        // and that lead points past the end of the far end: half the window
        // read as zeros and the filter learned from a reference it did not
        // have. Clamping the window to what had been written was worse again,
        // because it moves the alignment without telling the weights, and
        // weights mean nothing except at the alignment they were learned at --
        // the tail of every gap came out twice as loud as it went in. A small
        // lead overshoots by a few milliseconds at worst, and those samples
        // read as the silence they are.
        val base = head16 - delaySamples - samples + LEAD
        var refEnergy = 0.0
        for (n in 0 until samples + taps) {
            val value = farEndAt(base + n - taps)
            refEnergy += value * value
        }
        referenceEnergy = sqrt(refEnergy / (samples + taps))

        // Seed the sliding window with the taps that precede this frame.
        windowPower = 0.0
        for (k in 1..taps) {
            val value = farEndAt(base - k)
            windowPower += value * value
        }

        var residualEnergy = 0.0
        for (n in 0 until samples) {
            val at = base + n
            var estimate = 0.0
            for (k in 0 until taps) {
                estimate += weights[k] * farEndAt(at - k)
            }
            val error = residual[n] - estimate
            residual[n] = error
            residualEnergy += error * error

            // Adapt only where the far end is carrying enough to be adapted
            // to, and divide by a power that cannot approach zero.
            //
            // This is where the first version blew up. The update is
            // `step x error / power`, and most 20 ms of speech is silence: the
            // reference window goes quiet while the microphone is still
            // carrying the ring-out of the word before, so the error stays
            // large while the divisor collapses. With a regularisation of 1e-6
            // -- nothing, against sixteen-bit samples -- a single such sample
            // threw the weights far enough that the filter added more than it
            // removed, which the device reported as a cancellation of -2.89
            // and then -10.35.
            // Slid rather than recomputed: the window moves by one sample a
            // step, so the sum does too, and a thousand-tap filter does not
            // need a thousand multiplies to know how loud its input is.
            val entering = farEndAt(at)
            val leaving = farEndAt(at - taps)
            windowPower += entering * entering - leaving * leaving
            val power = REGULARISATION + maxOf(0.0, windowPower)
            if (power > ADAPT_MIN_POWER) {
                val gain = stepSize * error / power
                for (k in 0 until taps) {
                    weights[k] += gain * farEndAt(at - k)
                }
            }
        }

        // Only scored where there is something to cancel. With the speaker
        // between words the ratio is noise over noise, which reads as a
        // nonsense cancellation -- the device logged -2.74 against a reference
        // energy of 13 -- and a trust decision taken on that is a coin toss.
        if (referenceEnergy >= MIN_SCORING_REFERENCE && micEnergy > 0.0) {
            val cancelled = 1.0 - sqrt(residualEnergy / micEnergy)
            erle =
                if (erle == 0.0) cancelled
                else erle * (1 - ERLE_SMOOTHING) + cancelled * ERLE_SMOOTHING
            poorFrames = if (erle < POOR_CANCELLATION) poorFrames + 1 else 0
            // A filter that is adding energy is not converging slowly, it is
            // broken, and no amount of further adaptation recovers it. Start
            // again rather than carry the wreckage through the turn.
            if (cancelled < DIVERGED) {
                divergedFrames++
                if (divergedFrames >= DIVERGED_FRAMES) {
                    weights.fill(0.0)
                    erle = 0.0
                    divergedFrames = 0
                }
            } else {
                divergedFrames = 0
            }
        }
        cancelledFraction = erle
        return samples
    }

    /**
     * Find how late the microphone is, by looking for the far end inside it.
     *
     * A plain cross-correlation over [MAX_DELAY_SAMPLES], done on a coarse
     * stride first and refined around the winner, which is cheap enough to run
     * once a second and far cheaper than carrying thousands of taps.
     */
    private fun estimateDelay(frame: DoubleArray, samples: Int, head16: Long): Int {
        var frameEnergy = 0.0
        for (n in 0 until samples) frameEnergy += frame[n] * frame[n]
        if (frameEnergy < MIN_ALIGN_ENERGY * samples) return -1

        var bestLag = -1
        var bestScore = 0.0
        var lag = 0
        while (lag < MAX_DELAY_SAMPLES) {
            val score = correlation(frame, samples, head16 - lag - samples)
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
            lag += COARSE_STRIDE
        }
        if (bestLag < 0 || bestScore < MIN_ALIGN_CORRELATION) return -1

        var refined = bestLag
        var from = maxOf(0, bestLag - COARSE_STRIDE)
        val until = minOf(MAX_DELAY_SAMPLES, bestLag + COARSE_STRIDE)
        while (from < until) {
            val score = correlation(frame, samples, head16 - from - samples)
            if (score > bestScore) {
                bestScore = score
                refined = from
            }
            from++
        }
        return refined
    }

    private fun correlation(frame: DoubleArray, samples: Int, base: Long): Double {
        var dot = 0.0
        var energy = 0.0
        for (n in 0 until samples) {
            val value = farEndAt(base + n)
            dot += frame[n] * value
            energy += value * value
        }
        if (energy <= 0.0) return 0.0
        return dot * dot / energy
    }

    fun reset() {
        weights.fill(0.0)
        delaySamples = -1
        poorFrames = 0
        divergedFrames = 0
        erle = 0.0
        referenceEnergy = 0.0
        cancelledFraction = 0.0
    }

    companion object {
        const val RATE = 16_000

        /**
         * 128 ms of room and of whatever the playback head is lying about.
         *
         * It was 16, on the reasoning that a phone on a desk is dominated by
         * the direct path. A room is not: a second arrival 25 ms after the
         * first -- an ordinary reflection off a desk and a wall -- sits
         * outside a 16 ms filter, and what a filter cannot model it misreads.
         * With the short filter the test that reproduces it measured the
         * canceller *adding* 72 % to the frame; with this one it does not.
         * The cost is linear and small: a thousand taps over a 20 ms frame is
         * about sixty million multiply-adds a second, a few per cent of one
         * core, which is what the sliding power below is for.
         */
        const val TAPS = 2_048

        /**
         * NLMS step size. Below one for stability; well below it because this
         * filter is allowed to converge over a sentence rather than a word,
         * and a fast filter chases the angler's voice during double talk.
         */
        const val STEP_SIZE = 0.25

        /** One second of far end, which bounds any delay we could see. */
        const val RING_SAMPLES = RATE

        /** Up to 300 ms between the playback head and the microphone. */
        const val MAX_DELAY_SAMPLES = 4_800

        private const val COARSE_STRIDE = 16

        /** How far past the alignment the filter may look: 4 ms. */
        private const val LEAD = 512

        /**
         * How much of a frame's energy the chosen alignment has to account
         * for before it is believed, rather than the previous one reused.
         */
        private const val MIN_FINE_CORRELATION = 0.05
        /** Two seconds of poor cancellation before the path is re-measured. */
        private const val REALIGN_AFTER_POOR_FRAMES = 100

        /** Cancellation below this counts as poor. */
        private const val POOR_CANCELLATION = 0.25

        /** Far-end energy below which cancellation cannot be scored. */
        private const val MIN_SCORING_REFERENCE = 300.0

        private const val ERLE_SMOOTHING = 0.1
        /**
         * Floor under the power the update divides by, in sixteen-bit sample
         * units: the energy a reference window of RMS 300 would carry, so a
         * quiet moment cannot turn into a large correction.
         */
        private const val REGULARISATION = 2.3e7

        /** Reference power below which nothing is learned at all. */
        private const val ADAPT_MIN_POWER = 1e8

        /** Cancellation below which the filter is considered to have blown up. */
        private const val DIVERGED = -0.2
        private const val DIVERGED_FRAMES = 10
        private const val MIN_ALIGN_ENERGY = 10_000.0
        private const val MIN_ALIGN_CORRELATION = 1e6

        private const val OUT_PER_IN_NUM = 2L
        private const val OUT_PER_IN_DEN = 3L
        private const val IN_PER_OUT = 1.5
    }
}
