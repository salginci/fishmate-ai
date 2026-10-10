import { useEffect, useRef } from 'react'
import type { AssistantPhase } from '../live/FishingModeController'

type Props = {
  phase: AssistantPhase
  isMuted: boolean
  /** Loudness of what is leaving the speaker, 0 to 1, read per frame. */
  getLevel: () => number
}

const SIZE = 168
const RINGS = 3

/**
 * The assistant, drawn as a face that moves with its own voice.
 *
 * Driven by the playback waveform rather than by the `speaking` flag, so the
 * mouth follows syllables instead of opening for the whole turn. That is the
 * difference between something that looks alive and something that looks like
 * a loading indicator.
 *
 * Animated on a canvas inside `requestAnimationFrame` and deliberately outside
 * React: this reads a number sixty times a second, and routing that through
 * state would re-render the entire screen for one element.
 */
export function Avatar({ phase, isMuted, getLevel }: Props) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  // Refs, not deps: the animation loop must not be torn down and rebuilt
  // every time the phase changes mid-sentence.
  const phaseRef = useRef(phase)
  const mutedRef = useRef(isMuted)
  phaseRef.current = phase
  mutedRef.current = isMuted

  useEffect(() => {
    const canvas = canvasRef.current
    if (!canvas) return
    const ctx = canvas.getContext('2d')
    if (!ctx) return

    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    canvas.width = SIZE * dpr
    canvas.height = SIZE * dpr
    ctx.scale(dpr, dpr)

    const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

    let raf = 0
    let smoothed = 0
    let blink = 0
    let nextBlinkAt = performance.now() + 2_500

    const render = (now: number) => {
      raf = requestAnimationFrame(render)

      const currentPhase = phaseRef.current
      const muted = mutedRef.current
      const speaking = currentPhase === 'speaking'
      const raw = speaking ? getLevel() : 0
      // Attack fast, release slow: speech consonants are brief, and a symmetric
      // filter makes the mouth flutter instead of articulate.
      smoothed += (raw - smoothed) * (raw > smoothed ? 0.5 : 0.12)

      const accent =
        currentPhase === 'error' ? '#ff6b6b' : muted ? '#6b7a8c' : '#35c4f0'
      const idlePulse = reduceMotion ? 0 : Math.sin(now / 900) * 0.5 + 0.5
      const centre = SIZE / 2

      ctx.clearRect(0, 0, SIZE, SIZE)

      // Rings: energy while speaking, a slow breath while listening, still
      // when muted or broken — the state is readable at a glance, outdoors.
      const listening = currentPhase === 'listening' && !muted
      for (let i = RINGS; i >= 1; i--) {
        const spread = speaking ? smoothed * 26 : listening ? idlePulse * 7 : 0
        const radius = 44 + i * 13 + spread * (i / RINGS)
        ctx.beginPath()
        ctx.arc(centre, centre, radius, 0, Math.PI * 2)
        ctx.strokeStyle = accent
        ctx.globalAlpha = (speaking ? 0.3 : listening ? 0.16 : 0.08) * (1 - (i - 1) / RINGS)
        ctx.lineWidth = 2
        ctx.stroke()
      }
      ctx.globalAlpha = 1

      // Head
      const head = ctx.createRadialGradient(centre - 14, centre - 18, 6, centre, centre, 52)
      head.addColorStop(0, muted ? '#46525f' : '#1d4f63')
      head.addColorStop(1, '#0d1b24')
      ctx.beginPath()
      ctx.arc(centre, centre, 48, 0, Math.PI * 2)
      ctx.fillStyle = head
      ctx.fill()
      ctx.strokeStyle = accent
      ctx.globalAlpha = 0.5
      ctx.lineWidth = 2
      ctx.stroke()
      ctx.globalAlpha = 1

      // Eyes, with an occasional blink so a silent avatar still reads as awake.
      if (now > nextBlinkAt) {
        blink = 1
        nextBlinkAt = now + 2_200 + Math.random() * 3_000
      }
      blink = Math.max(0, blink - 0.12)
      const eyeOpen = 1 - blink
      ctx.fillStyle = accent
      for (const dx of [-16, 16]) {
        ctx.beginPath()
        ctx.ellipse(centre + dx, centre - 10, 5, 6 * eyeOpen + 0.5, 0, 0, Math.PI * 2)
        ctx.fill()
      }

      // Mouth: height follows the waveform, so it articulates rather than
      // simply opening for the duration of the turn.
      const openness = speaking ? Math.max(0.06, smoothed) : 0.06
      const mouthWidth = 30 + openness * 10
      const mouthHeight = 3 + openness * 24
      ctx.beginPath()
      ctx.ellipse(centre, centre + 20, mouthWidth / 2, mouthHeight / 2, 0, 0, Math.PI * 2)
      ctx.fillStyle = muted ? '#2a333d' : '#06202a'
      ctx.fill()
      ctx.strokeStyle = accent
      ctx.globalAlpha = 0.75
      ctx.lineWidth = 2
      ctx.stroke()
      ctx.globalAlpha = 1

      if (muted) {
        // An unmistakable "it cannot hear you", rather than a subtle tint.
        ctx.beginPath()
        ctx.moveTo(centre - 34, centre + 34)
        ctx.lineTo(centre + 34, centre - 34)
        ctx.strokeStyle = '#8795a6'
        ctx.lineWidth = 3
        ctx.stroke()
      }
    }

    raf = requestAnimationFrame(render)
    return () => cancelAnimationFrame(raf)
  }, [getLevel])

  return (
    <canvas
      ref={canvasRef}
      className="avatar"
      style={{ width: SIZE, height: SIZE }}
      role="img"
      aria-label={
        phase === 'speaking' ? 'FishMate is speaking' : isMuted ? 'Microphone muted' : 'FishMate is listening'
      }
    />
  )
}
