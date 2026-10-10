/**
 * PCM conversions for the Live API: 16-bit little-endian mono, 16 kHz in and
 * 24 kHz out. The Web Audio API works in float, the wire works in int16.
 */

/** Float samples in [-1, 1] to 16-bit PCM, clamped rather than wrapped. */
export function floatToPcm16(input: Float32Array): Int16Array {
  const out = new Int16Array(input.length)
  for (let i = 0; i < input.length; i++) {
    const s = Math.max(-1, Math.min(1, input[i] ?? 0))
    // Asymmetric on purpose: int16 holds -32768 but only +32767, and scaling
    // both by 32768 clips every full-scale positive peak.
    out[i] = s < 0 ? s * 0x8000 : s * 0x7fff
  }
  return out
}

/** 16-bit PCM back to float, for playback through an AudioBuffer. */
export function pcm16ToFloat(input: Int16Array): Float32Array {
  const out = new Float32Array(input.length)
  for (let i = 0; i < input.length; i++) out[i] = (input[i] ?? 0) / 0x8000
  return out
}

/**
 * Linear resampling to the Live API's input rate.
 *
 * `AudioContext({sampleRate: 16000})` is honoured by current Chrome, Firefox
 * and Safari, so this is a fallback rather than the normal path — but a
 * browser that silently hands back 48 kHz would otherwise make the model hear
 * everything at three times speed, which is a confusing failure to debug.
 */
export function resample(input: Float32Array, fromRate: number, toRate: number): Float32Array {
  if (fromRate === toRate) return input
  const ratio = fromRate / toRate
  const length = Math.floor(input.length / ratio)
  const out = new Float32Array(length)
  for (let i = 0; i < length; i++) {
    const position = i * ratio
    const index = Math.floor(position)
    const frac = position - index
    const a = input[index] ?? 0
    const b = input[index + 1] ?? a
    out[i] = a + (b - a) * frac
  }
  return out
}

/** Base64 for the wire. Chunked so a long buffer cannot blow the call stack. */
export function bytesToBase64(bytes: Uint8Array): string {
  let binary = ''
  const chunk = 0x8000
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk))
  }
  return btoa(binary)
}

export function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

/** RMS over 16-bit samples; the echo gate's only input. */
export function rms(samples: Int16Array): number {
  if (samples.length === 0) return 0
  let sum = 0
  for (let i = 0; i < samples.length; i++) {
    const s = samples[i] ?? 0
    sum += s * s
  }
  return Math.sqrt(sum / samples.length)
}
