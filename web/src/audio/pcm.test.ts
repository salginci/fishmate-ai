import { describe, expect, it } from 'vitest'
import { base64ToBytes, bytesToBase64, floatToPcm16, pcm16ToFloat, resample, rms } from './pcm'

describe('pcm', () => {
  it('clamps full-scale peaks instead of wrapping them', () => {
    // Scaling a positive peak by 32768 overflows int16 and wraps to a large
    // negative sample, which is audible as a crack on every loud syllable.
    const out = floatToPcm16(new Float32Array([1, -1, 2, -2]))
    expect(out[0]).toBe(32767)
    expect(out[1]).toBe(-32768)
    expect(out[2]).toBe(32767)
    expect(out[3]).toBe(-32768)
  })

  it('round-trips float and pcm within one quantisation step', () => {
    const input = new Float32Array([0, 0.25, -0.5, 0.75])
    const back = pcm16ToFloat(floatToPcm16(input))
    for (let i = 0; i < input.length; i++) {
      expect(Math.abs((back[i] ?? 0) - (input[i] ?? 0))).toBeLessThan(1 / 32767)
    }
  })

  it('resamples by the rate ratio', () => {
    // A browser that ignores the requested 16 kHz would otherwise make the
    // model hear everything at three times speed.
    const input = new Float32Array(480)
    expect(resample(input, 48_000, 16_000).length).toBe(160)
    expect(resample(input, 16_000, 16_000)).toBe(input)
  })

  it('round-trips base64 for a buffer larger than the call-stack chunk', () => {
    const bytes = new Uint8Array(70_000)
    for (let i = 0; i < bytes.length; i++) bytes[i] = i % 256
    expect(base64ToBytes(bytesToBase64(bytes))).toEqual(bytes)
  })

  it('measures rms', () => {
    expect(rms(new Int16Array([3, -3, 3, -3]))).toBeCloseTo(3)
    expect(rms(new Int16Array(0))).toBe(0)
  })
})
