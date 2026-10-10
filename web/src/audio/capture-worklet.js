/**
 * Captures microphone frames on the audio thread.
 *
 * The worklet only buffers and forwards: it deliberately holds no gating or
 * conversion logic, because anything here runs on a realtime thread where a
 * slow frame is an audible glitch. It emits fixed 20 ms blocks so the main
 * thread never has to re-chunk, matching the Live API guidance to avoid large
 * buffers.
 */
class CaptureProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super()
    const frameSamples = options?.processorOptions?.frameSamples ?? 320
    this.frameSamples = frameSamples
    this.buffer = new Float32Array(frameSamples)
    this.filled = 0
  }

  process(inputs) {
    const channel = inputs[0]?.[0]
    if (!channel) return true

    let offset = 0
    while (offset < channel.length) {
      const room = this.frameSamples - this.filled
      const take = Math.min(room, channel.length - offset)
      this.buffer.set(channel.subarray(offset, offset + take), this.filled)
      this.filled += take
      offset += take

      if (this.filled === this.frameSamples) {
        // Transferred, not copied: the buffer is handed over and replaced.
        const frame = this.buffer
        this.port.postMessage(frame, [frame.buffer])
        this.buffer = new Float32Array(this.frameSamples)
        this.filled = 0
      }
    }
    return true
  }
}

registerProcessor('fishmate-capture', CaptureProcessor)
