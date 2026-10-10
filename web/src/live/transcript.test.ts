import { describe, expect, it } from 'vitest'
import { appendTranscript, applyTranslation, MAX_TRANSCRIPT_LINES } from './transcript'

describe('transcript', () => {
  it('merges consecutive fragments from one speaker', () => {
    let lines = appendTranscript([], 'user', 'the water')
    lines = appendTranscript(lines, 'user', ' is clear')
    expect(lines).toHaveLength(1)
    expect(lines[0]?.text).toBe('the water is clear')
  })

  it('starts a new line when the speaker changes', () => {
    let lines = appendTranscript([], 'user', 'what lure?')
    lines = appendTranscript(lines, 'assistant', 'try a soft plastic')
    expect(lines).toHaveLength(2)
  })

  it('keeps the id but drops a stale translation when a line grows', () => {
    let lines = appendTranscript([], 'user', 'the water')
    const id = lines[0]!.id
    lines = applyTranslation(lines, id, 'the water', 'su')
    expect(lines[0]?.translation).toBe('su')

    lines = appendTranscript(lines, 'user', ' is clear')
    expect(lines[0]?.id).toBe(id)
    // The subtitle described a shorter sentence than the one now on screen.
    expect(lines[0]?.translation).toBeNull()
  })

  it('discards a translation for a stale version of a line', () => {
    let lines = appendTranscript([], 'user', 'the water')
    const stale = lines[0]!
    lines = appendTranscript(lines, 'user', ' is clear')
    lines = applyTranslation(lines, stale.id, stale.text, 'su')
    expect(lines[0]?.translation).toBeNull()
  })

  it('bounds the transcript', () => {
    let lines: ReturnType<typeof appendTranscript> = []
    for (let i = 0; i < MAX_TRANSCRIPT_LINES + 20; i++) {
      lines = appendTranscript(lines, i % 2 === 0 ? 'user' : 'assistant', `line ${i}`)
    }
    expect(lines).toHaveLength(MAX_TRANSCRIPT_LINES)
  })
})

describe('repetition', () => {
  it('counts a sentence the assistant restarted', async () => {
    const { countRepeatedSentences } = await import('./transcript')
    // What the echo loop looks like in a transcript: the model is cut off by
    // its own voice and begins the same sentence again.
    const text =
      'The water is very clear today. The water is very clear today. The water is very clear today.'
    expect(countRepeatedSentences(text)).toBe(2)
  })

  it('does not flag ordinary short replies', async () => {
    const { countRepeatedSentences } = await import('./transcript')
    // "Yes." twice in a conversation is normal, not a malfunction.
    expect(countRepeatedSentences('Yes. Okay. Yes. Mm.')).toBe(0)
  })

  it('ignores punctuation and casing differences', async () => {
    const { countRepeatedSentences } = await import('./transcript')
    expect(countRepeatedSentences('Try a lighter leader! try a lighter leader.')).toBe(1)
  })

  it('counts nothing for a healthy turn', async () => {
    const { countRepeatedSentences } = await import('./transcript')
    expect(
      countRepeatedSentences(
        'The water is clear today. Try a lighter leader and a smaller soft plastic.',
      ),
    ).toBe(0)
  })

  it('counts a repeat as a line is still being spoken', async () => {
    const { appendTranscript, RepeatCounter } = await import('./transcript')
    const counter = new RepeatCounter()
    // Fragments, the way transcription actually arrives.
    let lines = appendTranscript([], 'assistant', 'Cast along ')
    expect(counter.observe(lines[0]!)).toBe(0)
    lines = appendTranscript(lines, 'assistant', 'the rocks. Cast along the rocks.')
    expect(counter.observe(lines[0]!)).toBe(1)
  })

  it('keeps earlier repeats when the next turn is clean', async () => {
    const { appendTranscript, RepeatCounter } = await import('./transcript')
    const counter = new RepeatCounter()
    let lines = appendTranscript([], 'assistant', 'Cast along the rocks. Cast along the rocks.')
    expect(counter.observe(lines[lines.length - 1]!)).toBe(1)

    // A new turn: the angler speaks, then a clean answer starts a new line.
    lines = appendTranscript(lines, 'user', 'And the leader?')
    lines = appendTranscript(lines, 'assistant', 'Drop to a lighter fluorocarbon leader.')
    expect(counter.observe(lines[lines.length - 1]!)).toBe(1)
  })

  it('adds up repeats from consecutive turns', async () => {
    const { appendTranscript, RepeatCounter } = await import('./transcript')
    const counter = new RepeatCounter()
    let lines = appendTranscript([], 'assistant', 'Work it near the bottom. Work it near the bottom.')
    counter.observe(lines[lines.length - 1]!)
    lines = appendTranscript(lines, 'user', 'And now?')
    lines = appendTranscript(lines, 'assistant', 'Speed the retrieve up. Speed the retrieve up.')
    expect(counter.observe(lines[lines.length - 1]!)).toBe(2)
  })

  it('reset clears the running total for a new session', async () => {
    const { appendTranscript, RepeatCounter } = await import('./transcript')
    const counter = new RepeatCounter()
    const lines = appendTranscript([], 'assistant', 'Cast along the rocks. Cast along the rocks.')
    expect(counter.observe(lines[0]!)).toBe(1)
    counter.reset()
    expect(counter.total).toBe(0)
  })
})
