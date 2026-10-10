import { log } from '../core/log'
import { rms } from './pcm'

/**
 * Decides which captured frames reach the model while the assistant is
 * speaking out of a speaker.
 *
 * Ported from the Android `EchoGate`, including the two tuning mistakes that
 * only showed up on a real device (see `docs/GEMINI_LIVE_IMPLEMENTATION.md` ->
 * "The echo loop"). The browser's own `echoCancellation` constraint is better
 * than Android's platform canceller, but it does not win against laptop
 * speakers at arm's length, and the failure is the same: the model hears
 * itself, server-side VAD calls it a barge-in, it restarts, and the
 * conversation collapses — with the echo also making it answer in languages
 * nobody spoke.
 *
 * Muting during playback would fix it and remove barge-in with it, so instead
 * each frame is compared against a running estimate of the echo.
 */
export class EchoGate {
  private echoFloor = 0
  private loudFrames = 0
  private bargeInOpen = false
  private framesThisTurn = 0

  constructor(
    private readonly bargeInFactor = EchoGate.BARGE_IN_FACTOR,
    private readonly minBargeInRms = EchoGate.MIN_BARGE_IN_RMS,
    private readonly bargeInFrames = EchoGate.BARGE_IN_FRAMES,
    private readonly floorDecay = EchoGate.ECHO_FLOOR_DECAY,
    private readonly warmupFrames = EchoGate.WARMUP_FRAMES,
  ) {}

  /** Current echo estimate, for the diagnostics panel and tests. */
  get estimatedEchoRms(): number {
    return this.echoFloor
  }

  /**
   * @param speaking whether the assistant's audio is audible right now. False
   *   means there is no acoustic path to worry about, so capture runs open.
   */
  accepts(samples: Int16Array, speaking: boolean): boolean {
    if (!speaking) {
      // Reset, so the next turn measures this room at its current volume
      // rather than inheriting a stale estimate.
      this.reset()
      return true
    }
    // Latched: the server flushes playback a moment after accepting a
    // barge-in, and re-closing mid-sentence would clip the first words.
    if (this.bargeInOpen) return true

    const level = rms(samples)
    this.framesThisTurn++

    if (this.framesThisTurn <= this.warmupFrames) {
      // A spoken turn opens with near-silence before the first syllable
      // reaches the speaker. Seeding the floor from those frames put it at an
      // RMS of about 5 on a real phone, so the echo that followed at about
      // 3000 looked six hundred times louder than "the echo" and was accepted
      // as a barge-in. The warm-up measures the loudest part of the lead-in
      // instead, and admits nothing while it does.
      this.echoFloor = Math.max(this.echoFloor, level)
      this.loudFrames = 0
      return false
    }

    const threshold = Math.max(this.minBargeInRms, this.echoFloor * this.bargeInFactor)
    if (level < threshold) {
      // Echo rises fast and falls slowly: track the peak upward at once and
      // only drift back down. An averaging estimate seeded on quiet frames is
      // what produced the false barge-ins above, and adapting upward on loud
      // frames would instead let the floor chase the angler's own voice and
      // lock barge-in out entirely.
      this.echoFloor = Math.max(level, this.echoFloor * this.floorDecay)
      this.loudFrames = 0
      return false
    }

    this.loudFrames++
    if (this.loudFrames < this.bargeInFrames) return false

    this.bargeInOpen = true
    log.d('EchoGate', `barge-in accepted (rms=${level | 0} floor=${this.echoFloor | 0})`)
    return true
  }

  reset(): void {
    this.echoFloor = 0
    this.loudFrames = 0
    this.bargeInOpen = false
    this.framesThisTurn = 0
  }

  /**
   * How much louder than the echo a frame must be to count as speech. The
   * browser's echo canceller has already attenuated it, so real speech into
   * the microphone clears this comfortably. Biased high on purpose: a missed
   * barge-in costs one repeated sentence, a false one costs the conversation.
   */
  static readonly BARGE_IN_FACTOR = 3.5

  /** Absolute floor, for when playback is near-silent. */
  static readonly MIN_BARGE_IN_RMS = 1200

  /** 20 ms frames, so ten is 200 ms — longer than any transient, shorter than a word. */
  static readonly BARGE_IN_FRAMES = 10

  /** Per-frame downward drift of the estimate; it only falls, rises are immediate. */
  static readonly ECHO_FLOOR_DECAY = 0.995

  /** 300 ms of measuring before anything can be called a barge-in. */
  static readonly WARMUP_FRAMES = 15
}
