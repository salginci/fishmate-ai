package com.fishmate.ai

import com.fishmate.ai.live.RepeatCounter
import com.fishmate.ai.live.TranscriptLine
import com.fishmate.ai.live.TranscriptSpeaker
import com.fishmate.ai.live.append
import com.fishmate.ai.live.countRepeatedSentences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Transcript assembly.
 *
 * Live transcription arrives as fragments, so without merging the on-screen
 * transcript becomes one word per line and is unreadable.
 */
class TranscriptTest {

    @Test
    fun `consecutive fragments from one speaker merge into a line`() {
        val lines = emptyList<TranscriptLine>()
            .append(TranscriptSpeaker.ASSISTANT, "Bu")
            .append(TranscriptSpeaker.ASSISTANT, " kucuk")
            .append(TranscriptSpeaker.ASSISTANT, " bir soft plastic.")
        assertEquals(1, lines.size)
        assertEquals("Bu kucuk bir soft plastic.", lines.single().text)
    }

    @Test
    fun `speaker change starts a new line`() {
        val lines = emptyList<TranscriptLine>()
            .append(TranscriptSpeaker.USER, "Ne kullanayim?")
            .append(TranscriptSpeaker.ASSISTANT, "Kucuk sahteyle devam et.")
            .append(TranscriptSpeaker.USER, "Tamam.")
        assertEquals(3, lines.size)
        assertEquals(TranscriptSpeaker.USER, lines.first().speaker)
        assertEquals(TranscriptSpeaker.ASSISTANT, lines[1].speaker)
    }

    @Test
    fun `leading whitespace is trimmed on a new line`() {
        val lines = emptyList<TranscriptLine>().append(TranscriptSpeaker.USER, "   hello")
        assertEquals("hello", lines.single().text)
    }

    @Test
    fun `transcript is bounded so a long session cannot grow without limit`() {
        var lines = emptyList<TranscriptLine>()
        // Alternate speakers so every fragment becomes its own line.
        repeat(600) { index ->
            val speaker =
                if (index % 2 == 0) TranscriptSpeaker.USER else TranscriptSpeaker.ASSISTANT
            lines = lines.append(speaker, "line $index")
        }
        assertTrue("size=${lines.size}", lines.size <= 200)
        // The newest content is what is kept.
        assertTrue(lines.last().text.contains("599"))
    }

    // ---- repeat detection ------------------------------------------------
    // The echo loop's signature: the model restarts the same sentence, and
    // because restarts merge into the line being spoken the duplication shows
    // up inside one line. These pin the same thresholds as the web client's
    // `transcript.test.ts`, so the two clients report the same number for the
    // same session.

    @Test
    fun `counts a sentence the assistant restarted`() {
        val text = "Try casting along the rocks. Try casting along the rocks. " +
            "Try casting along the rocks."
        assertEquals(2, countRepeatedSentences(text))
    }

    @Test
    fun `short conversational fragments are not repeats`() {
        assertEquals(0, countRepeatedSentences("Yes. Okay. Yes. Mm."))
    }

    @Test
    fun `punctuation and case do not hide a repeat`() {
        assertEquals(1, countRepeatedSentences("Try a lighter leader! try a lighter leader."))
    }

    @Test
    fun `distinct advice is not counted as repetition`() {
        val text = "Work it near the bottom and pause. " +
            "Then speed up the retrieve a little."
        assertEquals(0, countRepeatedSentences(text))
    }

    @Test
    fun `turkish dotted capitals do not break the comparison`() {
        // `lowercase()` is locale-invariant on purpose: under a Turkish locale
        // I would fold to ı and the two sentences would compare unequal, so
        // the loop would go unreported on exactly the phones that have it.
        val text = "Iskorpit olabilir, dikkatli tut. iskorpit olabilir, dikkatli tut."
        assertEquals(1, countRepeatedSentences(text))
    }

    // ---- the running total ------------------------------------------------
    // Mirrors `RepeatCounter` in `web/src/live/transcript.test.ts`.

    @Test
    fun `a repeat is counted while the line is still being spoken`() {
        val counter = RepeatCounter()
        // Fragments, the way transcription actually arrives.
        var lines = emptyList<TranscriptLine>().append(TranscriptSpeaker.ASSISTANT, "Cast along ")
        assertEquals(0, counter.observe(lines.last()))
        lines = lines.append(TranscriptSpeaker.ASSISTANT, "the rocks. Cast along the rocks.")
        assertEquals(1, counter.observe(lines.last()))
    }

    @Test
    fun `earlier repeats survive a clean following turn`() {
        val counter = RepeatCounter()
        var lines = emptyList<TranscriptLine>()
            .append(TranscriptSpeaker.ASSISTANT, "Cast along the rocks. Cast along the rocks.")
        assertEquals(1, counter.observe(lines.last()))

        // A new turn: the angler speaks, then a clean answer starts a new line.
        lines = lines.append(TranscriptSpeaker.USER, "And the leader?")
        lines = lines.append(TranscriptSpeaker.ASSISTANT, "Drop to a lighter fluorocarbon leader.")
        assertEquals(1, counter.observe(lines.last()))
    }

    @Test
    fun `repeats from consecutive turns add up`() {
        val counter = RepeatCounter()
        var lines = emptyList<TranscriptLine>()
            .append(TranscriptSpeaker.ASSISTANT, "Work it near the bottom. Work it near the bottom.")
        counter.observe(lines.last())
        lines = lines.append(TranscriptSpeaker.USER, "And now?")
        lines = lines.append(TranscriptSpeaker.ASSISTANT, "Speed the retrieve up. Speed the retrieve up.")
        assertEquals(2, counter.observe(lines.last()))
    }

    @Test
    fun `reset clears the running total for a new session`() {
        val counter = RepeatCounter()
        val lines = emptyList<TranscriptLine>()
            .append(TranscriptSpeaker.ASSISTANT, "Cast along the rocks. Cast along the rocks.")
        assertEquals(1, counter.observe(lines.last()))
        counter.reset()
        assertEquals(0, counter.total)
    }
}
