package com.fishmate.ai

import com.fishmate.ai.audio.EchoCanceller
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The canceller is the first thing here that uses the far-end *waveform*
 * rather than its loudness, so these tests are about identification: given a
 * room that returns a delayed, filtered copy of what we played, can it find
 * the copy and take it out, and does it leave a voice alone while doing so.
 */
class EchoCancellerTest {

    private val rate = EchoCanceller.RATE
    private val frame = 320 // 20 ms

    /** Playback frames at 24 kHz for one 16 kHz-sample count. */
    private fun frames24(samples16: Int) = samples16 * 3 / 2

    private fun farEndChunk(random: Random, samples24: Int): ByteArray {
        val bytes = ByteArray(samples24 * 2)
        for (n in 0 until samples24) {
            // Band-limited noise: a random walk, which has the low-frequency
            // weight of speech rather than the flat spectrum of white noise,
            // and so exercises the filter the way a voice would.
            val value = (random.nextInt(-8_000, 8_000)).toShort()
            bytes[n * 2] = (value.toInt() and 0xFF).toByte()
            bytes[n * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun pcm(values: DoubleArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        for (n in values.indices) {
            val v = values[n].toInt().coerceIn(-32_768, 32_767)
            bytes[n * 2] = (v and 0xFF).toByte()
            bytes[n * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun rms(values: DoubleArray, count: Int): Double {
        var sum = 0.0
        for (n in 0 until count) sum += values[n] * values[n]
        return sqrt(sum / count)
    }

    /**
     * Run [turns] frames of pure echo through the canceller and report how
     * much of the last frame survived, as a fraction of what went in.
     */
    private fun converge(
        canceller: EchoCanceller,
        delay: Int,
        gain: Double,
        turns: Int,
        voice: ((Int) -> Double)? = null,
    ): Pair<Double, Double> {
        val random = Random(7)
        val residual = DoubleArray(frame)
        var head24 = 0L
        var lastIn = 0.0
        var lastOut = 0.0
        repeat(turns) {
            canceller.pushFarEnd(farEndChunk(random, frames24(frame)), 0, frames24(frame) * 2, head24)
            head24 += frames24(frame)
            // The room: what left the speaker `delay` samples ago, quieter.
            val end = canceller.farEndEnd
            val mic = DoubleArray(frame)
            for (n in 0 until frame) {
                val index = end - frame + n - delay
                mic[n] = gain * canceller.farEndAt(index) + (voice?.invoke(n) ?: 0.0)
            }
            lastIn = rms(mic, frame)
            canceller.process(pcm(mic), frame * 2, head24, residual)
            lastOut = rms(residual, frame)
        }
        return lastIn to lastOut
    }

    @Test
    fun `the room's copy of what we played is found and taken out`() {
        // The whole premise. If this does not hold, nothing downstream can
        // tell the assistant's voice from the angler's, which is the state
        // every threshold in EchoGate was fitted to.
        val canceller = EchoCanceller()
        val (input, output) = converge(canceller, delay = 160, gain = 0.5, turns = 120)

        assertTrue("nothing was cancelled: $input in, $output out", output < input * 0.35)
        assertTrue("the delay was never found", canceller.isAligned)
    }

    @Test
    fun `a voice over the echo survives the subtraction`() {
        // The case the product is for. The angler speaks while the assistant
        // is talking: the echo goes, they stay.
        val canceller = EchoCanceller()
        converge(canceller, delay = 160, gain = 0.5, turns = 120)

        var voiceRms = 0.0
        val speak: (Int) -> Double = { n ->
            val value = 3_000.0 * kotlin.math.sin(2.0 * Math.PI * 220.0 * n / rate)
            voiceRms = 3_000.0 / sqrt(2.0)
            value
        }
        val (input, output) = converge(canceller, delay = 160, gain = 0.5, turns = 6, voice = speak)

        assertTrue("the voice was cancelled along with the echo", output > voiceRms * 0.5)
        assertTrue("the echo came back with it: $input in, $output out", output < input)
    }

    @Test
    fun `sound with nothing to do with the far end is left alone`() {
        // Wind, waves, a gull. None of it correlates with the speaker, so none
        // of it may be subtracted -- and none of it may be mistaken for echo
        // either, which is what a level-based rule does on a windy shore.
        val canceller = EchoCanceller()
        converge(canceller, delay = 160, gain = 0.5, turns = 120)

        val random = Random(99)
        val residual = DoubleArray(frame)
        val noise = DoubleArray(frame) { random.nextInt(-2_000, 2_000).toDouble() }
        val before = rms(noise, frame)
        canceller.pushFarEnd(ByteArray(frames24(frame) * 2), 0, frames24(frame) * 2, canceller.farEndEnd * 3 / 2)
        canceller.process(pcm(noise), frame * 2, canceller.farEndEnd * 3 / 2, residual)
        val after = rms(residual, frame)

        assertTrue("uncorrelated sound was eaten: $before -> $after", after > before * 0.8)
    }

    @Test
    fun `a far end full of gaps does not blow the filter up`() {
        // Speech is mostly silence -- between words, between sentences, and
        // whenever the server is between blobs -- while the microphone keeps
        // carrying the ring-out of what came before. That combination is what
        // the update divides by, and the first version of this filter divided
        // by nearly nothing and added more energy than it removed: the device
        // reported cancellations of -2.89 and -10.35 before this test existed.
        val canceller = EchoCanceller()
        val random = Random(3)
        val residual = DoubleArray(frame)
        var head24 = 0L
        var worst = 1.0

        repeat(150) { turn ->
            // Three frames of speech, two of silence, as speech actually goes.
            val speaking = turn % 5 < 3
            val chunk =
                if (speaking) farEndChunk(random, frames24(frame))
                else ByteArray(frames24(frame) * 2)
            canceller.pushFarEnd(chunk, 0, frames24(frame) * 2, head24)
            head24 += frames24(frame)

            val end = canceller.farEndEnd
            val mic = DoubleArray(frame)
            for (n in 0 until frame) {
                // The room is still ringing even where the far end has
                // stopped, which is the whole difficulty.
                mic[n] = 0.5 * canceller.farEndAt(end - frame + n - 160) +
                    0.2 * canceller.farEndAt(end - frame + n - 400)
            }
            val before = rms(mic, frame)
            canceller.process(pcm(mic), frame * 2, head24, residual)
            val after = rms(residual, frame)
            if (before > 1.0) worst = minOf(worst, 1.0 - after / before)
        }

        assertTrue("the filter diverged: worst cancellation $worst", worst > -0.5)
    }
}
