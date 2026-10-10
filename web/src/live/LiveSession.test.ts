import { describe, expect, it } from 'vitest'
import { computeProactiveDelay, LiveSession, parseDurationMillis } from './LiveSession'
import { canStartNewSession, isExpired } from './events'
import type { LiveCredentials } from '../data/types'

const credentials = (over: Partial<LiveCredentials> = {}): LiveCredentials => ({
  token: 'tok',
  ws_url: 'wss://example.invalid/ws',
  model: 'models/gemini-3.8-live',
  expires_at: new Date(Date.now() + 30 * 60_000).toISOString(),
  new_session_expires_at: new Date(Date.now() + 60_000).toISOString(),
  setup: {
    model: 'models/gemini-3.8-live',
    generationConfig: { responseModalities: ['AUDIO'] },
    systemInstruction: { parts: [{ text: 'be useful' }] },
  },
  input_sample_rate_hz: 16_000,
  output_sample_rate_hz: 24_000,
  input_audio_mime: 'audio/pcm;rate=16000',
  user_brief: null,
  ...over,
})

const session = () =>
  new LiveSession(
    () => {},
    () => {},
    async () => null,
  )

describe('setup frame', () => {
  it('sends the backend setup verbatim, with no config wrapper', () => {
    // `config` is an SDK abstraction. Sending it is rejected with
    // `Unknown name "config" at 'setup': Cannot find field.`
    const frame = JSON.parse(session().buildSetupFrame(credentials(), null))
    expect(frame.setup.model).toBe('models/gemini-3.8-live')
    expect(frame.setup.generationConfig.responseModalities).toEqual(['AUDIO'])
    expect(frame.setup.config).toBeUndefined()
    expect(frame.config).toBeUndefined()
  })

  it('adds an empty sessionResumption on a fresh session', () => {
    // Present but empty is what asks the server to start issuing handles.
    const frame = JSON.parse(session().buildSetupFrame(credentials(), null))
    expect(frame.setup.sessionResumption).toEqual({})
  })

  it('carries the handle when resuming', () => {
    const frame = JSON.parse(session().buildSetupFrame(credentials(), 'handle-42'))
    expect(frame.setup.sessionResumption).toEqual({ handle: 'handle-42' })
  })

  it('replaces any sessionResumption the backend sent', () => {
    // The handle is client-side state the server cannot know, so ours wins.
    const creds = credentials({ setup: { model: 'm', sessionResumption: { handle: 'stale' } } })
    const frame = JSON.parse(session().buildSetupFrame(creds, 'fresh'))
    expect(frame.setup.sessionResumption).toEqual({ handle: 'fresh' })
  })
})

describe('token deadlines', () => {
  it('separates "still valid" from "can open a new session"', () => {
    // The two-deadline bug: a token stays valid for about 30 minutes but can
    // only *open* a session for about one. Checking only `expires_at` made
    // every drop longer than a minute permanent.
    const creds = credentials({
      expires_at: new Date(Date.now() + 25 * 60_000).toISOString(),
      new_session_expires_at: new Date(Date.now() - 5_000).toISOString(),
    })
    expect(isExpired(creds)).toBe(false)
    expect(canStartNewSession(creds)).toBe(false)
  })

  it('accepts a token inside both windows', () => {
    const creds = credentials()
    expect(isExpired(creds)).toBe(false)
    expect(canStartNewSession(creds)).toBe(true)
  })

  it('reports an elapsed token as expired', () => {
    const creds = credentials({ expires_at: new Date(Date.now() - 1_000).toISOString() })
    expect(isExpired(creds)).toBe(true)
  })
})

describe('goAway', () => {
  it('parses the protobuf duration string', () => {
    expect(parseDurationMillis('9.5s')).toBe(9_500)
    expect(parseDurationMillis('10s')).toBe(10_000)
    expect(parseDurationMillis(null)).toBeNull()
    expect(parseDurationMillis('soon')).toBeNull()
  })

  it('reconnects before the server cuts the socket', () => {
    expect(computeProactiveDelay(9_500)).toBe(8_000)
    // A tiny or missing timeLeft must not cause an immediate reconnect storm.
    expect(computeProactiveDelay(100)).toBe(250)
    expect(computeProactiveDelay(null)).toBe(1_000)
  })
})
