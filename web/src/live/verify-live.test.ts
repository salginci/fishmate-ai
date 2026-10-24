import { describe, expect, it } from 'vitest'
import WebSocketImpl from 'ws'
import { LiveSession } from './LiveSession'
import type { LiveCredentials } from '../data/types'
import type { LiveEvent } from './events'

/**
 * A real Gemini Live session, driven by the web client's own code.
 *
 * Skipped by default: no automated test in this repository contacts Google,
 * for the same reason the backend keeps `make verify-live` separate — a suite
 * that needs network and quota is a suite people stop running. Enable with:
 *
 *   FISHMATE_VERIFY_LIVE=1 npm test
 *
 * It needs the local backend on :8099 with a usable `GEMINI_API_KEY`.
 *
 * What it proves that a unit test cannot: that the `setup` frame this client
 * builds is accepted by the live service. Every mistake in that frame — the
 * `config` wrapper, a missing `models/` prefix, a stray locked field — fails
 * here and nowhere else.
 */
const ENABLED = process.env.FISHMATE_VERIFY_LIVE === '1'
const API = process.env.FISHMATE_API ?? 'http://localhost:8099'

// No fallbacks. These are credentials for a real account on a real server, and
// a default that works is a default that gets committed -- this one was, and
// it reached every commit in the repository before anyone noticed. A missing
// variable has to fail loudly here rather than quietly sign somebody in.
function required(name: string): string {
  const value = process.env[name]
  if (!value) {
    throw new Error(
      `${name} is not set. ` +
        'make verify-live-web needs FISHMATE_EMAIL and FISHMATE_PASSWORD ' +
        'for an account on FISHMATE_API; keep them in your shell or .env, ' +
        'never in this file.',
    )
  }
  return value
}

const EMAIL = ENABLED ? required('FISHMATE_EMAIL') : ''
const PASSWORD = ENABLED ? required('FISHMATE_PASSWORD') : ''

async function mint(): Promise<LiveCredentials> {
  const login = await fetch(`${API}/api/v1/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email: EMAIL, password: PASSWORD }),
  })
  if (!login.ok) throw new Error(`login failed: ${login.status}`)
  const { access_token } = (await login.json()) as { access_token: string }

  const session = await fetch(`${API}/api/v1/live/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${access_token}` },
    body: JSON.stringify({ language: 'en', expects_video: false }),
  })
  if (!session.ok) throw new Error(`mint failed: ${session.status}`)
  return (await session.json()) as LiveCredentials
}

describe.skipIf(!ENABLED)('Gemini Live (real service)', () => {
  it('accepts our setup frame and answers with native audio', async () => {
    // The browser's WebSocket is not in Node 20; the session only needs the
    // standard interface, which `ws` provides.
    ;(globalThis as any).WebSocket = WebSocketImpl

    const credentials = await mint()
    const events: LiveEvent[] = []
    let audioBytes = 0

    const done = new Promise<void>((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('timed out waiting for audio')), 45_000)
      const session = new LiveSession(
        (event) => {
          events.push(event)
          if (event.type === 'audio') audioBytes += event.pcm.byteLength
          if (event.type === 'ready') {
            session.sendText('Say the single word: ready. Nothing else.')
          }
          if (event.type === 'turnComplete' && audioBytes > 0) {
            clearTimeout(timer)
            session.disconnect()
            resolve()
          }
          if (event.type === 'error' && event.fatal) {
            clearTimeout(timer)
            reject(new Error(`${event.code}: ${event.message}`))
          }
        },
        () => {},
        async () => null,
      )
      session.connect(credentials)
    })

    await done

    // A verification tool should say what it saw, the way the backend's
    // `verify_live_api.py` prints its checks.
    const kinds = new Set(events.map((e) => e.type))
    console.info(
      `  setup accepted · ${(audioBytes / 1024).toFixed(1)} KB native audio · ` +
        `events: ${[...kinds].sort().join(', ')}`,
    )

    expect(events.some((e) => e.type === 'ready')).toBe(true)
    // Native audio end to end: no STT/TTS path anywhere in this client.
    expect(audioBytes).toBeGreaterThan(0)
  }, 60_000)
})
