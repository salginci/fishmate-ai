package com.fishmate.ai

import com.fishmate.ai.audio.EchoGate
import kotlin.math.sin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The echo gate is what keeps the assistant from interrupting itself on the
 * loudspeaker, and its thresholds are pure tuning — the kind that breaks
 * silently and only shows up as a conversation that collapses on a real phone.
 *
 * It has two failure directions and both are tested here, because fixing one
 * is how you create the other. Too permissive and the assistant hears its own
 * voice, stops, restarts and collapses. Too strict and the angler cannot cut
 * in at all, which is what the previous rule did: it compared the microphone
 * against a floor measured from the microphone, so the bar sat at roughly
 * 3.5× the assistant's own peak and no human voice could clear it.
 *
 * The gate now gets the playback level as a reference, so these tests always
 * supply one. Passing none means "the speaker is silent", which is a real
 * state and tested too, but it is not what echo looks like.
 */
class EchoGateTest {

    /**
     * One 20 ms frame of 16 kHz mono PCM at the given amplitude.
     *
     * The default 500 Hz sits in the speech band. [windFrame] supplies the
     * other case the gate has to tell apart.
     */
    private fun frame(amplitude: Int, samples: Int = 320, hz: Double = 500.0): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val value = (amplitude * sin(2.0 * Math.PI * hz * i / 16_000.0)).toInt().toShort()
            bytes[i * 2] = (value.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    /** Wind, handling rumble, engine drone: loud, and almost all of it low. */
    private fun windFrame(amplitude: Int) = frame(amplitude, hz = 70.0)

    /** A sine's RMS is its amplitude over root two; handy for reading the numbers. */
    private fun rmsOf(amplitude: Int) = amplitude / 1.4142

    private fun EchoGate.feed(
        amplitude: Int,
        times: Int,
        speaking: Boolean,
        reference: Float,
    ): Int {
        var passed = 0
        repeat(times) {
            if (accepts(frame(amplitude), 640, speaking, reference)) passed++
        }
        return passed
    }

    @Test
    fun `passes everything when the assistant is not speaking`() {
        val gate = EchoGate()
        // Full duplex off the speaker: even near-silence must reach the model,
        // or server-side VAD never sees the start of a sentence.
        assertTrue(gate.accepts(frame(80), 640, speaking = false))
        assertTrue(gate.accepts(frame(9000), 640, speaking = false))
    }

    @Test
    fun `steady echo never opens the gate`() {
        // The failure this class exists to prevent: the assistant hearing
        // itself, reporting a barge-in, and restarting into its own echo.
        val gate = EchoGate()
        val passed = gate.feed(amplitude = 4200, times = 400, speaking = true, reference = 0.8f)
        assertTrue("echo must never be forwarded, got $passed frames", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `echo that gets louder still never opens the gate`() {
        // Volume raised mid-turn, or the phone brought closer. The learned
        // gain has to rise with it, or the angler's own assistant trips it.
        val gate = EchoGate()
        var passed = 0
        for (amplitude in 1500..6000 step 150) {
            passed += gate.feed(amplitude, times = 8, speaking = true, reference = 0.8f)
        }
        assertTrue("rising echo opened the gate after $passed frames", passed == 0)
    }

    @Test
    fun `echo during the gaps between words does not open the gate`() {
        // The failure this reproduces was reported from a real session: with
        // nobody speaking, the assistant interrupted itself continuously.
        //
        // The microphone lags the speaker by the acoustic path plus capture
        // buffering, so at every word boundary the reference had already
        // dropped while the microphone still carried the echo of the word
        // before. Judged against the instantaneous level that looked like
        // "speaker silent, someone is talking", and speech is mostly gaps.
        val gate = EchoGate()
        var passed = 0
        // Twelve words: 300 ms of speech, then a 200 ms gap in the reference
        // while the microphone keeps carrying the tail. The gap is longer
        // than [EchoGate.BARGE_IN_FRAMES] on purpose — a shorter one cannot
        // open the gate whatever the rule, so a test using one would pass
        // against the very bug it claims to catch.
        repeat(12) {
            passed += gate.feed(amplitude = 4200, times = 15, speaking = true, reference = 0.75f)
            // Reference has stopped, microphone has not.
            passed += gate.feed(amplitude = 4200, times = 10, speaking = true, reference = 0.0f)
        }
        assertTrue("the assistant interrupted itself on $passed frames", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `a real pause still allows an interruption`() {
        // The other side of the same coin: holding the reference up must not
        // hold it up forever, or the gaps that make barge-in possible are
        // gone with it.
        val gate = EchoGate()
        gate.feed(amplitude = 4200, times = 60, speaking = true, reference = 0.75f)

        // The assistant stops. Nothing from the room for a moment.
        gate.feed(amplitude = 200, times = 20, speaking = true, reference = 0.0f)
        // Now the angler speaks into that pause.
        //
        // Louder than this test used to use, because [MIN_BARGE_IN_RMS] was
        // raised to sit above a measured room-noise floor. That is a real
        // cost and it is recorded here rather than hidden: while the floor
        // is doing the reference's job, a quiet voice in a pause no longer
        // interrupts. What a voice into the phone actually measures on this
        // device has not been captured yet, so the floor is provisional.
        val passed = gate.feed(
            amplitude = 9000,
            times = EchoGate.BARGE_IN_FRAMES + 2,
            speaking = true,
            reference = 0.0f,
        )
        assertTrue("a genuine interruption in a pause was blocked", passed > 0)
    }

    @Test
    fun `mid-sentence a voice must clear the leak, not merely the floor`() {
        // What replaced "speech in a gap between words opens the gate": that
        // test asserted the behaviour that made the assistant interrupt
        // itself, because a momentary dip in the reference is not a pause —
        // the microphone is still full of the previous word.
        //
        // The cost is stated here rather than hidden: while the assistant is
        // actually talking, cutting in needs a voice clearly above the leak.
        // The absolute floor only applies once playback has genuinely
        // stopped, which is the case the test above covers.
        val gate = EchoGate()
        gate.feed(amplitude = 4200, times = 60, speaking = true, reference = 0.75f)

        val quiet = gate.feed(amplitude = 2000, times = 20, speaking = true, reference = 0.75f)
        assertTrue("a voice at the level of the leak must not open it", quiet == 0)

        val loud = gate.feed(amplitude = 14000, times = EchoGate.BARGE_IN_FRAMES + 2, speaking = true, reference = 0.75f)
        assertTrue("a voice well above the leak was blocked", loud > 0)
    }

    @Test
    fun `a voice clearly louder than the measured echo opens the gate`() {
        val gate = EchoGate()
        // Calibrate on the leak.
        gate.feed(amplitude = 3000, times = 60, speaking = true, reference = 0.7f)
        val echo = gate.estimatedEchoRms
        // In speech-band terms, so below the raw RMS: the 300 Hz high-pass
        // takes a slice off even an in-band tone. The behaviour below is what
        // matters; this only checks the estimate is of the right order.
        assertTrue("the gate should have learned the leak, got $echo", echo > rmsOf(3000) * 0.6)
        assertTrue("the estimate should not exceed the signal, got $echo", echo <= rmsOf(3000))

        // Someone talking into the phone, comfortably past that leak.
        val passed = gate.feed(amplitude = 12000, times = 20, speaking = true, reference = 0.7f)
        assertTrue("real speech over playback was blocked", passed > 0)
    }

    @Test
    fun `a short transient does not open the gate`() {
        // A rod tapping the rail, a wave slapping the hull.
        val gate = EchoGate()
        gate.feed(amplitude = 3000, times = 60, speaking = true, reference = 0.7f)
        val passed = gate.feed(amplitude = 14000, times = EchoGate.BARGE_IN_FRAMES - 1, speaking = true, reference = 0.7f)
        assertTrue("a transient was forwarded as speech", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `room tone in a gap is not mistaken for speech`() {
        // The quiet-reference path must still have a floor, or wind and
        // handling noise open the gate every time the assistant pauses.
        val gate = EchoGate()
        val passed = gate.feed(amplitude = 900, times = 100, speaking = true, reference = 0.0f)
        assertTrue("room tone opened the gate $passed times", passed == 0)
    }

    @Test
    fun `the gate stays open for the rest of the turn once it opens`() {
        // The server flushes playback a moment after accepting a barge-in;
        // reassessing mid-sentence would clip the angler's first words.
        val gate = EchoGate()
        gate.feed(amplitude = 3000, times = 60, speaking = true, reference = 0.7f)
        gate.feed(amplitude = 12000, times = EchoGate.BARGE_IN_FRAMES + 2, speaking = true, reference = 0.7f)
        assertTrue(gate.isBargeInOpen)

        // Quiet frames in the middle of a sentence must still get through.
        val passed = gate.feed(amplitude = 400, times = 20, speaking = true, reference = 0.7f)
        assertTrue("the latch dropped mid-sentence", passed == 20)
    }

    @Test
    fun `resets between turns`() {
        val gate = EchoGate()
        gate.feed(amplitude = 3000, times = 60, speaking = true, reference = 0.7f)
        gate.feed(amplitude = 12000, times = EchoGate.BARGE_IN_FRAMES + 2, speaking = true, reference = 0.7f)
        assertTrue(gate.isBargeInOpen)

        // Turn over: not speaking resets, so the next turn measures this
        // room's echo again rather than inheriting a stale latch.
        assertTrue(gate.accepts(frame(100), 640, speaking = false))
        assertFalse(gate.isBargeInOpen)

        val passed = gate.feed(amplitude = 4200, times = 100, speaking = true, reference = 0.8f)
        assertTrue("the latch survived the turn boundary", passed == 0)
    }

    @Test
    fun `the measured device case, where the reference lies and the room is silent`() {
        // Replays what a Galaxy A35 actually produced, which no synthetic
        // case had caught. The room reads 1 when genuinely idle, and during
        // playback the microphone carried 1500-3300 of echo at moments when
        // the playback reference read 0.02 — the tail of the word just
        // played, arriving after the reference had already fallen.
        //
        // Every false interruption landed exactly there: reference "silent",
        // so the bar collapsed to the safety floor, which the echo cleared.
        val gate = EchoGate()
        repeat(200) { gate.accepts(frame(2), 640, speaking = false, reference = 0f) }

        var accepted = 0
        repeat(20) {
            // Loud playback, honestly reported.
            repeat(12) {
                if (gate.accepts(frame(4600), 640, speaking = true, reference = 0.7f)) accepted++
            }
            // The reference has fallen; the microphone has not.
            repeat(10) {
                if (gate.accepts(frame(4600), 640, speaking = true, reference = 0.02f)) accepted++
            }
        }
        assertTrue("the assistant interrupted itself on $accepted frames", accepted == 0)
    }

    @Test
    fun `a greeting that opens quietly is not cut off by its own echo`() {
        // The second measured A35 case, from the log of a greeting that was
        // flushed 1.2 s after it started. The opening of the utterance is its
        // quietest part: playback reported 0.03-0.08 throughout, never enough
        // to calibrate against, so the only measurement the gate ever had was
        // the warmup window at 1359. The same leak reached 2985 a few seconds
        // later, cleared a bar of 1359 x 1.8, and the assistant flushed its own
        // sentence and dropped into Listening without having said anything.
        val gate = EchoGate()
        repeat(200) { gate.accepts(frame(2), 640, speaking = false, reference = 0f) }

        // 1359 and 2985 of speech-band RMS, as sine amplitudes.
        gate.feed(amplitude = 2130, times = EchoGate.WARMUP_FRAMES, speaking = true, reference = 0.05f)
        val passed = gate.feed(amplitude = 4690, times = 80, speaking = true, reference = 0.05f)

        assertTrue("the greeting interrupted itself on $passed frames", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `a frame quieter than the leak predicted for it stays shut`() {
        // The third A35 case: at 14:34:58 the gate accepted a frame of 4433
        // against a bar of 3842 while its own estimate of the leak for that
        // same frame was 5915. It had measured the gain correctly and then
        // ignored it, because the bar was built only from the loudest frame
        // seen so far — which had decayed.
        val gate = EchoGate()
        repeat(200) { gate.accepts(frame(2), 640, speaking = false, reference = 0f) }

        // Learn the room at a moderate playback level.
        gate.feed(
            amplitude = 3000,
            times = EchoGate.WARMUP_FRAMES + EchoGate.CALIBRATION_FRAMES + 20,
            speaking = true,
            reference = 0.3f,
        )

        // Now the assistant gets loud. Three times the reference is about
        // three times the leak in the same room, and the gate has to apply
        // that even though it has never seen a frame this loud.
        val passed = gate.feed(
            amplitude = 7000,
            times = EchoGate.BARGE_IN_FRAMES + 4,
            speaking = true,
            reference = 0.9f,
        )

        assertTrue("echo louder than anything measured was forwarded ($passed frames)", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `a question asked into a quiet moment of the assistant is heard`() {
        // The device measurement, replayed with its own numbers. At 15:33:20
        // the angler asked a question while the assistant was talking: the
        // microphone read 5585 of speech-band energy, the leak predicted for
        // that instant was 470, and the frame was rejected anyway because the
        // bar still carried the loudest moment of the turn, 8611. The server
        // never received the question, so it never answered.
        //
        // The bar is now the prediction, so a loud passage no longer stands in
        // front of a quiet one, and this is heard at full volume -- which is
        // the point. The answer to this case used to be ducking the speaker
        // for 600 ms, and that was audible: a product whose subject is the
        // Live API cannot dip its own voice to work out whether it is being
        // spoken to.
        val gate = EchoGate()
        repeat(200) { gate.accepts(frame(2), 640, speaking = false, reference = 0f) }

        // A loud turn, so the leak is honestly measured and the bar is earned.
        gate.feed(amplitude = 7000, times = 60, speaking = true, reference = 0.9f)

        // The angler speaks into a dip in the assistant's own speech. Half a
        // second of it, because the reference is a *memory* of loudness and
        // has to fade before the dip counts as one: about six frames at
        // [EchoGate.REFERENCE_RELEASE], then [EchoGate.BARGE_IN_FRAMES] of
        // speech on top. That is the cost of covering the room's ring-out,
        // roughly a quarter of a second before the assistant stops, and it is
        // recorded here rather than discovered on a shore.
        val passed = gate.feed(
            amplitude = 9600,
            times = 30,
            speaking = true,
            reference = 0.04f,
        )

        assertTrue("the question must be heard without touching the volume", passed > 0)
        assertTrue(gate.isBargeInOpen)
    }

    @Test
    fun `the angler's own voice cannot raise the bar above itself`() {
        // Measured on a Galaxy A35: the residual echo peaked at 3096 and the
        // angler's voice at 4133, but the gate had learned a peak of 4075 —
        // because a voice rejected for being below the bar was then counted
        // as evidence of the leak and pushed the bar higher. The same trap
        // as the echo gain, in a second place, and it locks out the only
        // person the gate exists to hear.
        // Built with the margin that was shipped when this was measured, so
        // the voice lands *below* the bar — which is the only situation where
        // a rejected frame can be mistaken for evidence of the leak. At the
        // margin in force now the voice clears the bar outright and the trap
        // never springs; the guard stays because the margin is a tuned number
        // and this failure must not come back with it.
        val gate = EchoGate(bargeInFactor = 1.8)
        // A turn of genuine echo, honestly referenced.
        gate.feed(amplitude = 4400, times = EchoGate.WARMUP_FRAMES + 20, speaking = true, reference = 0.6f)
        val afterEcho = gate.measuredEchoPeak

        // Now the angler speaks, during a dip in the reference.
        repeat(40) { gate.accepts(frame(5900), 640, speaking = true, reference = 0.02f) }

        // Whatever the gate learned, it must not have learned it from them.
        assertTrue(
            "the voice taught the gate to ignore it: " +
                "peak went from $afterEcho to ${gate.measuredEchoPeak}",
            gate.measuredEchoPeak <= afterEcho,
        )

    }

    @Test
    fun `wind does not interrupt the assistant however loud it gets`() {
        // Reported from a real session: level alone cannot separate a gust
        // from a voice, because a gust is simply loud. What separates them is
        // where the energy sits — wind is almost entirely below the speech
        // band.
        val gate = EchoGate()
        gate.feed(amplitude = 3000, times = 60, speaking = true, reference = 0.7f)

        var passed = 0
        for (amplitude in intArrayOf(4000, 9000, 16000, 24000)) {
            repeat(50) {
                if (gate.accepts(windFrame(amplitude), 640, speaking = true, reference = 0.7f)) {
                    passed++
                }
            }
        }
        assertTrue("wind opened the gate on $passed frames", passed == 0)
        assertFalse(gate.isBargeInOpen)
    }

    @Test
    fun `wind in a genuine pause is still not speech`() {
        // The quiet-reference path drops the level bar, so without the band
        // test this is where a gust would always get through.
        val gate = EchoGate()
        var passed = 0
        repeat(100) {
            if (gate.accepts(windFrame(18000), 640, speaking = true, reference = 0f)) passed++
        }
        assertTrue("wind in a pause opened the gate on $passed frames", passed == 0)
    }

    @Test
    fun `a voice through wind still gets through`() {
        // The band test must not become a mute of its own: someone shouting
        // into the phone on a windy shore is still interrupting.
        val gate = EchoGate()
        val mixed = ByteArray(640)
        val voice = frame(9000)
        val wind = windFrame(12000)
        for (i in 0 until 320) {
            val v = ((voice[i * 2 + 1].toInt() shl 8) or (voice[i * 2].toInt() and 0xFF)).toShort()
            val w = ((wind[i * 2 + 1].toInt() shl 8) or (wind[i * 2].toInt() and 0xFF)).toShort()
            val sum = (v + w).coerceIn(-32768, 32767)
            mixed[i * 2] = (sum and 0xFF).toByte()
            mixed[i * 2 + 1] = ((sum shr 8) and 0xFF).toByte()
        }
        // Quiet playback through the warm-up, so the leak the bar is set from
        // is small and the voice has to clear only that.
        gate.feed(amplitude = 60, times = EchoGate.WARMUP_FRAMES + 5, speaking = true, reference = 0.5f)

        var passed = 0
        repeat(EchoGate.BARGE_IN_FRAMES + 4) {
            if (gate.accepts(mixed, 640, speaking = true, reference = 0f)) passed++
        }
        assertTrue("a voice through wind was blocked", passed > 0)
    }

    @Test
    fun `the bar follows the room, so a quiet phone is not deaf`() {
        // The device-independence property. An absolute RMS threshold tuned
        // in one room on one handset cannot be right on another: microphone
        // sensitivity and input gain vary by more between phones than rooms
        // vary between quiet and noisy. A sensitive device that reports every
        // level at half the size must still hear a voice.
        val sensitive = EchoGate(minBargeInRms = 100.0)
        // A quiet room, heard with the assistant silent.
        repeat(400) { sensitive.accepts(frame(300), 640, speaking = false, reference = 0f) }

        // The turn opens: the gate measures the leak before judging anything,
        // so a near-silent playback is what it calibrates on here.
        sensitive.feed(amplitude = 60, times = EchoGate.WARMUP_FRAMES + 5, speaking = true, reference = 0.5f)

        // A voice a few times above that room, during playback.
        val passed = sensitive.feed(
            amplitude = 1800,
            times = EchoGate.BARGE_IN_FRAMES + 2,
            speaking = true,
            reference = 0f,
        )
        assertTrue("a voice well above a quiet room was blocked", passed > 0)
    }

    @Test
    fun `a noisy room raises its own bar`() {
        // The other half: the same ratio must reject a room that is simply
        // loud, without anyone having to pick a number for it.
        val noisy = EchoGate(minBargeInRms = 100.0)
        repeat(400) { noisy.accepts(frame(4000), 640, speaking = false, reference = 0f) }

        val passed = noisy.feed(amplitude = 4000, times = 60, speaking = true, reference = 0f)
        assertTrue("room noise opened the gate on $passed frames", passed == 0)
    }

    @Test
    fun `with no reference wired in it degrades to an absolute threshold`() {
        // Defensive: if the player ever stops reporting a level, the gate must
        // still block quiet frames rather than either opening wide or welding
        // itself shut.
        val gate = EchoGate()
        assertTrue(gate.feed(amplitude = 500, times = 30, speaking = true, reference = 0f) == 0)
        val passed = gate.feed(amplitude = 9000, times = EchoGate.BARGE_IN_FRAMES + 2, speaking = true, reference = 0f)
        assertTrue("loud speech blocked with no reference", passed > 0)
    }
}
