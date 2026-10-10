import { log } from '../core/log'
import { EchoGate } from './EchoGate'
import { floatToPcm16, resample } from './pcm'
import workletUrl from './capture-worklet.js?url'

const TAG = 'AudioCapture'

/** The Live API's input rate. Not negotiable, and different from the output rate. */
export const INPUT_SAMPLE_RATE = 16_000

/** 20 ms at 16 kHz. */
const FRAME_SAMPLES = 320

export type CaptureOptions = {
  /** Called with one 20 ms frame of 16-bit PCM that passed the echo gate. */
  onFrame: (pcm: Int16Array) => void
  /** Whether the assistant is audible right now; drives the echo gate. */
  isSpeaking: () => boolean
}

/**
 * Microphone capture at the Live API's 16 kHz, with echo suppression.
 *
 * `echoCancellation` is requested from `getUserMedia` because without it the
 * model hears itself through the speakers and interrupts itself continuously.
 * The browser's canceller is better than Android's, but it is still not enough
 * on laptop speakers, so frames additionally pass [EchoGate].
 */
export class AudioCapture {
  private context: AudioContext | null = null
  private stream: MediaStream | null = null
  private node: AudioWorkletNode | null = null
  private source: MediaStreamAudioSourceNode | null = null
  private readonly gate = new EchoGate()

  /** Set by the controller; suppresses outgoing audio without closing the mic. */
  muted = false

  get echoFloor(): number {
    return this.gate.estimatedEchoRms
  }

  async start(options: CaptureOptions): Promise<void> {
    if (this.context) return

    this.stream = await navigator.mediaDevices.getUserMedia({
      audio: {
        echoCancellation: true,
        noiseSuppression: true,
        autoGainControl: true,
        channelCount: 1,
      },
    })

    // Asking for the rate up front avoids resampling entirely on current
    // browsers; `resample` below is the fallback if one ignores it.
    const context = new AudioContext({ sampleRate: INPUT_SAMPLE_RATE })
    this.context = context
    if (context.state === 'suspended') await context.resume()
    if (context.sampleRate !== INPUT_SAMPLE_RATE) {
      log.w(TAG, `context is ${context.sampleRate}Hz, resampling to ${INPUT_SAMPLE_RATE}Hz`)
    }

    await context.audioWorklet.addModule(workletUrl)
    const node = new AudioWorkletNode(context, 'fishmate-capture', {
      numberOfInputs: 1,
      numberOfOutputs: 0,
      processorOptions: { frameSamples: FRAME_SAMPLES },
    })
    this.node = node

    node.port.onmessage = (event: MessageEvent<Float32Array>) => {
      const floats =
        context.sampleRate === INPUT_SAMPLE_RATE
          ? event.data
          : resample(event.data, context.sampleRate, INPUT_SAMPLE_RATE)
      const pcm = floatToPcm16(floats)

      // The gate is fed every frame even while muted, so its echo estimate
      // stays current and un-muting does not start from a cold floor.
      const accepted = this.gate.accepts(pcm, options.isSpeaking())
      if (!this.muted && accepted) options.onFrame(pcm)
    }

    this.source = context.createMediaStreamSource(this.stream)
    this.source.connect(node)
    log.i(TAG, `capture started rate=${context.sampleRate} frame=${FRAME_SAMPLES} samples`)
  }

  stop(): void {
    this.node?.port.close()
    this.node?.disconnect()
    this.source?.disconnect()
    this.stream?.getTracks().forEach((track) => track.stop())
    void this.context?.close()
    this.node = null
    this.source = null
    this.stream = null
    this.context = null
    this.gate.reset()
    log.i(TAG, 'capture stopped')
  }
}
