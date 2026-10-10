import { describe, expect, it } from 'vitest'
import { EchoGate } from './EchoGate'

/**
 * The gate is what keeps the assistant from interrupting itself on a speaker,
 * and its thresholds are pure tuning — the kind that breaks silently and only
 * shows up as a conversation that collapses in a real room.
 */
describe('EchoGate', () => {
  /** One 20 ms frame of 16 kHz mono PCM at the given amplitude. */
  const frame = (amplitude: number, samples = 320): Int16Array => {
    const out = new Int16Array(samples)
    for (let i = 0; i < samples; i++) out[i] = Math.round(amplitude * Math.sin((2 * Math.PI * i) / 32))
    return out
  }

  const feed = (gate: EchoGate, amplitude: number, times: number, speaking: boolean): number => {
    let passed = 0
    for (let i = 0; i < times; i++) if (gate.accepts(frame(amplitude), speaking)) passed++
    return passed
  }

  it('passes everything when the assistant is not speaking', () => {
    const gate = new EchoGate()
    // Full duplex while silent: even quiet frames must reach the model, or
    // server-side VAD never sees the start of a sentence.
    expect(gate.accepts(frame(80), false)).toBe(true)
    expect(gate.accepts(frame(20_000), false)).toBe(true)
  })

  it('rejects steady echo while the assistant speaks', () => {
    const gate = new EchoGate()
    expect(feed(gate, 2_000, 100, true)).toBe(0)
  })

  it('does not treat the echo after a quiet lead-in as speech', () => {
    // The exact failure seen on a Galaxy A35: a turn opens with near-silence,
    // the floor was seeded from it, and the speech echo that followed was
    // accepted as a barge-in seven times a minute with nobody in the room.
    const gate = new EchoGate()
    feed(gate, 10, 8, true)
    expect(feed(gate, 4_200, 100, true)).toBe(0)
  })

  it('admits nothing during the warm-up', () => {
    const gate = new EchoGate()
    expect(feed(gate, 25_000, 12, true)).toBe(0)
  })

  it('lets a real barge-in through over loud echo', () => {
    const gate = new EchoGate()
    feed(gate, 4_200, 60, true)
    expect(feed(gate, 28_000, 20, true)).toBeGreaterThan(0)
  })

  it('ignores a short transient', () => {
    const gate = new EchoGate()
    feed(gate, 2_000, 60, true)
    // A chair creak or a door: loud, but over in less than the 200 ms a
    // barge-in has to sustain.
    expect(feed(gate, 25_000, 4, true)).toBe(0)
    expect(gate.accepts(frame(2_000), true)).toBe(false)
  })

  it('stays open once a barge-in is accepted', () => {
    const gate = new EchoGate()
    feed(gate, 2_000, 60, true)
    feed(gate, 25_000, 15, true)
    // Quieter trailing syllables of the same sentence must still pass.
    expect(gate.accepts(frame(3_000), true)).toBe(true)
  })

  it('re-measures the echo floor on the next turn', () => {
    const gate = new EchoGate()
    feed(gate, 2_000, 60, true)
    feed(gate, 25_000, 15, true)
    gate.accepts(frame(100), false) // turn ends
    expect(feed(gate, 2_000, 60, true)).toBe(0)
  })
})
