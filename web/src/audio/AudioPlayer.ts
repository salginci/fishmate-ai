import { log } from '../core/log'
import { pcm16ToFloat } from './pcm'

const TAG = 'AudioPlayer'

/** The Live API's output rate. Different from the 16 kHz input rate. */
export const OUTPUT_SAMPLE_RATE = 24_000

/**
 * Jitter buffer: how far ahead of the audio clock the next chunk is scheduled.
 *
 * The Live API streams audio at essentially exactly real time — measured at a
 * wall-clock-to-audio ratio of 0.99 to 1.01 — in chunks whose spacing was
 * measured at p50 74 ms, p90 152 ms and peaks of 187-263 ms. Two consequences
 * follow, and both were wrong in the first version:
 *
 * 1. There is no slack to refill a buffer with. Whatever lead is taken at the
 *    start of a turn is all there will ever be, and a ratio slightly above 1.0
 *    means it slowly *drains* over a long answer.
 * 2. The lead therefore has to cover the worst chunk gap outright. At 20 ms it
 *    covered none of them and speech broke up mid-word on almost every
 *    sentence, which is what made it sound robotic.
 *
 * Android never had this: `AudioTrack.write()` blocks and the hardware buffer
 * absorbs the jitter. In the browser the buffer has to be explicit.
 *
 * It adapts because the right value is a property of the network, not of the
 * code: start low so a good connection stays responsive, and grow only when
 * the schedule actually runs dry. Every increase is latency the listener
 * feels, so it is bounded.
 */
const INITIAL_LEAD_S = 0.22
const LEAD_GROWTH_S = 0.12
const MAX_LEAD_S = 0.6

/**
 * A dry schedule is only a stutter if speech was still in flight. Past this
 * much silence it is just the gap between turns, which is not a fault and
 * must not inflate the buffer for the rest of the session.
 */
const STUTTER_WINDOW_S = 0.4

/**
 * Plays the model's native audio: 16-bit PCM at 24 kHz.
 *
 * Chunks are scheduled back to back on the Web Audio clock rather than played
 * on arrival, because gaps between `AudioBufferSourceNode`s are audible as
 * clicks in continuous speech.
 *
 * [flush] is what implements barge-in: every scheduled source is stopped and
 * the schedule is reset, so the model stops mid-word instead of talking over
 * the angler for the second or two it had already buffered.
 */
export class AudioPlayer {
  private context: AudioContext | null = null
  private analyser: AnalyserNode | null = null
  private levelBuffer: Float32Array<ArrayBuffer> | null = null
  private scheduled = new Set<AudioBufferSourceNode>()
  private nextStartTime = 0
  private onPlayingChange: ((playing: boolean) => void) | null = null
  private lastPlaying = false

  /** Times the schedule fell behind mid-speech, i.e. audible breaks. */
  underruns = 0

  private leadSeconds = INITIAL_LEAD_S

  start(onPlayingChange?: (playing: boolean) => void): void {
    if (this.context) return
    const context = new AudioContext({ sampleRate: OUTPUT_SAMPLE_RATE })
    this.context = context
    // Everything is routed through an analyser so the avatar can be driven by
    // the actual waveform rather than by a guess at when speech is happening.
    this.analyser = context.createAnalyser()
    this.analyser.fftSize = 1024
    this.analyser.smoothingTimeConstant = 0.6
    this.analyser.connect(context.destination)
    this.levelBuffer = new Float32Array(this.analyser.fftSize)
    this.onPlayingChange = onPlayingChange ?? null
    this.nextStartTime = 0
    this.underruns = 0
    this.leadSeconds = INITIAL_LEAD_S
    void context.resume()
    log.i(TAG, `player started rate=${context.sampleRate}`)
  }

  /**
   * Loudness of what is leaving the speaker right now, roughly 0 to 1.
   *
   * Read per animation frame by the avatar, so it is a plain getter rather
   * than state: pushing this through React sixty times a second would re-render
   * the whole screen for a number only one element uses.
   */
  getOutputLevel(): number {
    const analyser = this.analyser
    const buffer = this.levelBuffer
    if (!analyser || !buffer) return 0
    analyser.getFloatTimeDomainData(buffer)
    let sum = 0
    for (let i = 0; i < buffer.length; i++) {
      const sample = buffer[i] ?? 0
      sum += sample * sample
    }
    // Speech rarely exceeds about 0.3 RMS, so scale it into a usable range.
    return Math.min(1, Math.sqrt(sum / buffer.length) * 3.2)
  }

  /**
   * True while audio is still scheduled to come out of the speaker.
   *
   * The echo gate reads this, so it has to describe the *speaker*, not the
   * queue: reporting false while the last buffer is still sounding would open
   * the microphone onto the tail of the model's own sentence.
   */
  get playing(): boolean {
    const context = this.context
    if (!context) return false
    return this.nextStartTime > context.currentTime
  }

  enqueue(pcm: Int16Array): void {
    const context = this.context
    if (!context) return

    const floats = pcm16ToFloat(pcm)
    const buffer = context.createBuffer(1, floats.length, OUTPUT_SAMPLE_RATE)
    // `copyToChannel` insists on a Float32Array backed by a plain ArrayBuffer,
    // which the generic return type does not promise.
    buffer.copyToChannel(floats as Float32Array<ArrayBuffer>, 0)

    const source = context.createBufferSource()
    source.buffer = buffer
    source.connect(this.analyser ?? context.destination)

    // Keep at least a jitter buffer's worth of lead.
    const now = context.currentTime
    let startAt = this.nextStartTime
    if (startAt < now + this.leadSeconds) {
      const silentFor = now - this.nextStartTime
      // Only a break *inside* an answer counts: between turns the schedule is
      // legitimately empty, and treating that as a fault would grow the buffer
      // on every single reply.
      if (this.nextStartTime > 0 && silentFor < STUTTER_WINDOW_S) {
        this.underruns++
        this.leadSeconds = Math.min(this.leadSeconds + LEAD_GROWTH_S, MAX_LEAD_S)
        log.d(TAG, `underrun; jitter buffer now ${(this.leadSeconds * 1000) | 0}ms`)
      }
      startAt = now + this.leadSeconds
    }
    source.start(startAt)
    this.nextStartTime = startAt + buffer.duration

    this.scheduled.add(source)
    source.onended = () => {
      this.scheduled.delete(source)
      this.notify()
    }
    this.notify()
  }

  /**
   * Drop everything still to be played (barge-in, or leaving Fishing Mode).
   *
   * Both halves matter: stopping the scheduled sources silences what the audio
   * clock already owns, and resetting `nextStartTime` stops the next chunk
   * being scheduled behind audio that no longer exists.
   */
  flush(): void {
    for (const source of this.scheduled) {
      source.onended = null
      try {
        source.stop()
      } catch {
        // Already finished; nothing to stop.
      }
      source.disconnect()
    }
    this.scheduled.clear()
    // Zero rather than `currentTime`: a flush is a barge-in or a stop, and the
    // next chunk begins a new answer, which must not be charged as a stutter.
    this.nextStartTime = 0
    this.notify()
    log.d(TAG, 'playback flushed (interruption)')
  }

  stop(): void {
    this.flush()
    this.analyser?.disconnect()
    this.analyser = null
    this.levelBuffer = null
    void this.context?.close()
    this.context = null
    this.notify()
    log.i(TAG, 'player stopped')
  }

  private notify(): void {
    const playing = this.playing
    if (playing === this.lastPlaying) return
    this.lastPlaying = playing
    this.onPlayingChange?.(playing)
  }
}
