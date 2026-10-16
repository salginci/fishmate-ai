package com.fishmate.ai

import com.fishmate.ai.audio.FarEndRing
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring is the part of echo cancellation that can be checked without a
 * handset: whether the far end comes back at the microphone's rate, in the
 * right place, and whether asking for audio that was never played returns
 * silence rather than whatever was in the buffer last.
 */
class FarEndRingTest {

    private fun pcm(values: IntArray): ByteArray {
        val bytes = ByteArray(values.size * 2)
        for (n in values.indices) {
            bytes[n * 2] = (values[n] and 0xFF).toByte()
            bytes[n * 2 + 1] = ((values[n] shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    @Test
    fun `audio comes back at the capture rate, two samples for every three`() {
        val ring = FarEndRing()
        // 24 kHz worth of a straight ramp, which resamples to a ramp.
        val input = IntArray(300) { it * 10 }
        ring.push(pcm(input), 0, input.size * 2, 0)

        assertEquals("300 output frames are 200 capture frames", 200L, ring.filled)
        // The ramp survives: sample k at 16 kHz is sample 1.5k at 24 kHz.
        for (k in 0 until 200) {
            val expected = 1.5 * k * 10
            assertTrue(
                "sample $k drifted: ${ring.at(k.toLong())} vs $expected",
                abs(ring.at(k.toLong()) - expected) < 15,
            )
        }
    }

    @Test
    fun `a frame is taken from where playback had reached`() {
        val ring = FarEndRing()
        val input = IntArray(1_500) { if (it < 750) 0 else 4_000 }
        ring.push(pcm(input), 0, input.size * 2, 0)

        // Playback head at the end of what was pushed: the frame before it is
        // the loud half.
        val out = ShortArray(320)
        ring.copyFrom(ring.cursorFor(1_500) - out.size, out)
        assertTrue("the loud half should be here", out.all { it > 3_000 })

        // Half a second earlier, in the silent half.
        ring.copyFrom(ring.cursorFor(480) - out.size, out)
        assertTrue("the quiet half should be here", out.all { it.toInt() == 0 })
    }

    @Test
    fun `audio that was never played reads as silence, not as stale buffer`() {
        // A session that has just started, or playback starved: the canceller
        // must be told there was nothing to cancel rather than handed whatever
        // the ring held from the turn before.
        val ring = FarEndRing()
        ring.push(pcm(IntArray(300) { 8_000 }), 0, 600, 0)

        val out = ShortArray(320)
        ring.copyFrom(ring.cursorFor(30_000), out)
        assertTrue("beyond what was played must be silent", out.all { it.toInt() == 0 })

        ring.reset()
        ring.copyFrom(ring.cursorFor(300), out)
        assertTrue("after a reset there is nothing to return", out.all { it.toInt() == 0 })
    }
}
