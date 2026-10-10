export type TranscriptSpeaker = 'user' | 'assistant'

export type TranscriptLine = {
  /** Stable across merges, so an arriving translation finds its line. */
  id: number
  speaker: TranscriptSpeaker
  text: string
  atEpochMs: number
  /**
   * Subtitle in the reader's language, filled in asynchronously. Null renders
   * as nothing rather than a placeholder: a missing subtitle must not look
   * like a broken turn.
   */
  translation: string | null
}

export const MAX_TRANSCRIPT_LINES = 200

let nextId = 0

/**
 * Append transcript text, merging into the previous line from the same speaker.
 *
 * Live transcription arrives as fragments ("the", " water", " is clear"), so
 * without merging the transcript becomes one word per line and is unreadable.
 */
export function appendTranscript(
  lines: TranscriptLine[],
  speaker: TranscriptSpeaker,
  text: string,
): TranscriptLine[] {
  const last = lines[lines.length - 1]
  let merged: TranscriptLine[]
  if (last && last.speaker === speaker) {
    merged = [
      ...lines.slice(0, -1),
      {
        ...last,
        text: (last.text + text).trimStart(),
        // Keep the id so a pending translation still matches, but drop the
        // translation itself: it described a shorter sentence.
        translation: null,
      },
    ]
  } else {
    merged = [
      ...lines,
      { id: ++nextId, speaker, text: text.trimStart(), atEpochMs: Date.now(), translation: null },
    ]
  }
  // Bound it: a two-hour session would otherwise grow without limit.
  return merged.length > MAX_TRANSCRIPT_LINES ? merged.slice(-MAX_TRANSCRIPT_LINES) : merged
}

/**
 * Attach a subtitle to a line.
 *
 * A no-op if the line has since grown or scrolled away, so a slow translation
 * can never caption a shorter version of the sentence than the one on screen.
 */
export function applyTranslation(
  lines: TranscriptLine[],
  id: number,
  sourceText: string,
  translation: string,
): TranscriptLine[] {
  const index = lines.findIndex((line) => line.id === id)
  if (index < 0) return lines
  const line = lines[index]
  if (!line || line.text !== sourceText || line.translation !== null) return lines
  const next = [...lines]
  next[index] = { ...line, translation }
  return next
}


/** Lowercase, strip punctuation, collapse whitespace. */
function normalize(text: string): string {
  return text
    .toLowerCase()
    .replace(/[.,!?;:…"'`´‘’“”()-]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/**
 * Running total of repeated sentences across a session.
 *
 * Incremental because the alternative — rescanning the whole transcript on
 * every arriving fragment — spends the main thread's budget looking for
 * stutter several times a second, while the avatar is animating on that same
 * thread. Only the line currently being spoken can still change, so only that
 * one is rescanned; a line's id changing is the signal that its count is final.
 *
 * Mirrors `RepeatCounter` in `android/.../live/RepeatCounter.kt`.
 */
export class RepeatCounter {
  private settled = 0
  private inCurrentLine = 0
  private lineId: number | null = null

  reset(): void {
    this.settled = 0
    this.inCurrentLine = 0
    this.lineId = null
  }

  /** Record the assistant line as it stands now, and return the session total. */
  observe(line: TranscriptLine): number {
    if (line.id !== this.lineId) {
      this.settled += this.inCurrentLine
      this.lineId = line.id
      this.inCurrentLine = 0
    }
    this.inCurrentLine = countRepeatedSentences(line.text)
    return this.settled + this.inCurrentLine
  }

  get total(): number {
    return this.settled + this.inCurrentLine
  }
}

/**
 * Count sentences the assistant said more than once.
 *
 * This is the symptom to watch for in a hands-free session. When the model
 * hears its own voice, server-side VAD reads it as a barge-in, the model stops
 * and starts the same sentence again — so the transcript fills with the same
 * phrase over and over. Because restarts merge into the line already being
 * spoken, the repetition usually appears *within* one line rather than across
 * lines, which is why this looks inside the text rather than comparing lines.
 *
 * Short fragments are ignored: "yes", "okay" and "mm" legitimately recur in
 * conversation and would otherwise swamp the count.
 */
export function countRepeatedSentences(text: string): number {
  const sentences = text
    .split(/(?<=[.!?…])\s+|\n+/)
    .map(normalize)
    .filter((sentence) => sentence.split(' ').length >= 4)

  const seen = new Map<string, number>()
  for (const sentence of sentences) seen.set(sentence, (seen.get(sentence) ?? 0) + 1)

  let repeats = 0
  for (const count of seen.values()) if (count > 1) repeats += count - 1
  return repeats
}
