package com.fishmate.ai.live

/**
 * Running total of repeated sentences across a session.
 *
 * Incremental because the alternative — rescanning the whole transcript on
 * every arriving fragment — spends the main thread's budget looking for
 * stutter several times a second, while the avatar is animating on that same
 * thread. Only the line currently being spoken can still change, so only that
 * one is rescanned; a line's id changing is the signal that its count is final.
 *
 * Not thread-safe, and does not need to be: the Live event loop runs on a
 * single dispatcher.
 *
 * Mirrors `RepeatCounter` in `web/src/live/transcript.ts`.
 */
class RepeatCounter {
    private var settled = 0
    private var inCurrentLine = 0
    private var lineId = 0L

    fun reset() {
        settled = 0
        inCurrentLine = 0
        lineId = 0L
    }

    /** Record the assistant line as it stands now, and return the session total. */
    fun observe(line: TranscriptLine): Int {
        if (line.id != lineId) {
            settled += inCurrentLine
            lineId = line.id
            inCurrentLine = 0
        }
        inCurrentLine = countRepeatedSentences(line.text)
        return total
    }

    val total: Int get() = settled + inCurrentLine
}
