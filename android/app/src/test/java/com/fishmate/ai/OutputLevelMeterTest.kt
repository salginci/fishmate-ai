package com.fishmate.ai

import com.fishmate.ai.audio.OutputLevelMeter
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The meter exists so the avatar's mouth lines up with the sound.
 *
 * The failure it guards against is silent and hard to spot by eye: taking the
 * loudness of a chunk when it is *written* rather than when it *plays* moves
 * the mouth a device buffer early, which reads as a badly dubbed film rather
 * than as a bug. So the alignment is asserted directly against frame
 * positions.
 */
class OutputLevelMeterTest {

    private val sampleRate = 24_000
    private val windowMs = 20
    private val windowFrames = sampleRate * windowMs / 1000

    /** [frames] of 24 kHz mono PCM at the given amplitude. */
    private fun pcm(amplitude: Int, frames: Int): ByteArray {
        val bytes = ByteArray(frames * 2)
        for (i in 0 until frames) {
            val value = (amplitude * sin(2.0 * Math.PI * i / 48.0)).toInt().toShort()
            bytes[i * 2] = (value.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun meter() = OutputLevelMeter(sampleRate, windowMs)

    @Test
    fun `reports nothing before anything is recorded`() {
        assertEquals(0f, meter().levelAt(0), 0f)
        assertEquals(0f, meter().levelAt(50_000), 0f)
    }

    @Test
    fun `loudness is reported against the frame it plays at, not when written`() {
        val meter = meter()
        // Silence first, then a loud burst one second in — the shape of a reply
        // that starts after a pause.
        meter.record(pcm(0, sampleRate), 0, sampleRate * 2, 0)
        meter.record(pcm(20_000, windowFrames), 0, windowFrames * 2, sampleRate.toLong())

        assertEquals("silence must read as silence", 0f, meter.levelAt(0), 0.01f)
        assertEquals("silence must read as silence", 0f, meter.levelAt(12_000), 0.01f)
        assertTrue(
            "the burst must register at the frame it plays at",
            meter.levelAt(sampleRate + 10L) > 0.5f,
        )
    }

    @Test
    fun `a frame past the end of what was recorded reads as silent`() {
        val meter = meter()
        meter.record(pcm(20_000, windowFrames), 0, windowFrames * 2, 0)

        assertTrue(meter.levelAt(0) > 0.5f)
        assertEquals(
            "nothing is scheduled there yet",
            0f,
            meter.levelAt(windowFrames.toLong()),
            0f,
        )
    }

    @Test
    fun `level scales with amplitude and saturates rather than exceeding one`() {
        val quiet = meter().apply { record(pcm(2_000, windowFrames), 0, windowFrames * 2, 0) }
        val loud = meter().apply { record(pcm(20_000, windowFrames), 0, windowFrames * 2, 0) }
        val full = meter().apply { record(pcm(32_767, windowFrames), 0, windowFrames * 2, 0) }

        assertTrue(quiet.levelAt(0) < loud.levelAt(0))
        assertTrue("speech should not sit at the very bottom", quiet.levelAt(0) > 0.05f)
        assertTrue("must never drive the mouth past fully open", full.levelAt(0) <= 1f)
        assertEquals("loud speech should saturate", 1f, full.levelAt(0), 0.001f)
    }

    @Test
    fun `reset clears measurements so a flushed track cannot read stale audio`() {
        // This is the barge-in case: AudioTrack's playback head restarts at
        // zero after flush(), so windows filed under old frame positions would
        // otherwise be read back as if they were the new reply.
        val meter = meter()
        meter.record(pcm(20_000, windowFrames), 0, windowFrames * 2, 0)
        assertTrue(meter.levelAt(0) > 0.5f)

        meter.reset()
        assertEquals(0f, meter.levelAt(0), 0f)
    }

    @Test
    fun `a long reply wraps the ring without reporting the wrong frame`() {
        val meter = meter()
        // Far more windows than the ring holds, written as the pump would.
        val totalWindows = 600
        for (w in 0 until totalWindows) {
            val amplitude = if (w == totalWindows - 1) 20_000 else 0
            meter.record(
                pcm(amplitude, windowFrames),
                0,
                windowFrames * 2,
                (w.toLong() * windowFrames),
            )
        }

        val lastFrame = (totalWindows - 1).toLong() * windowFrames
        assertTrue("the newest window must survive", meter.levelAt(lastFrame) > 0.5f)
        assertEquals(
            "a long-overwritten position must read as silent, not as a stale level",
            0f,
            meter.levelAt(0),
            0f,
        )
    }

    @Test
    fun `an odd trailing byte is ignored rather than crashing`() {
        val meter = meter()
        val bytes = pcm(20_000, windowFrames)
        // A short write can land mid-sample; AudioTrack reports whole bytes.
        meter.record(bytes, 0, bytes.size - 1, 0)
        assertTrue(meter.levelAt(0) > 0.5f)
    }

    @Test
    fun `an offset write is filed from the given frame`() {
        val meter = meter()
        val bytes = pcm(20_000, windowFrames * 2)
        // The pump re-enters write() with an offset after a partial write.
        meter.record(bytes, windowFrames * 2, windowFrames * 2, 1_000)
        assertTrue(meter.levelAt(1_000) > 0.5f)
        assertEquals(0f, meter.levelAt(999), 0f)
    }
}
