import { log } from '../core/log'
import { base64ToBytes, bytesToBase64 } from '../audio/pcm'
import type { LiveCredentials } from '../data/types'
import {
  canStartNewSession,
  isExpired,
  type GroundingSource,
  type LiveConnectionState,
  type LiveEvent,
  type ToolCallRequest,
  type ToolResult,
} from './events'

const TAG = 'GeminiLive'

const MAX_RECONNECT_ATTEMPTS = 6
const BASE_RECONNECT_DELAY_MS = 500

/**
 * A Gemini Live session over a raw WebSocket.
 *
 * The browser talks to Google directly — the backend never proxies audio or
 * video, it only mints the constrained ephemeral token. See
 * `docs/GEMINI_LIVE_IMPLEMENTATION.md` for the wire details this depends on.
 */
export class LiveSession {
  private socket: WebSocket | null = null
  private credentials: LiveCredentials | null = null
  private resumptionHandle: string | null = null
  private closedByUs = false
  private attempt = 0
  private reconnectTimer: number | null = null

  constructor(
    private readonly onEvent: (event: LiveEvent) => void,
    private readonly onConnectionState: (state: LiveConnectionState) => void,
    /** Mints fresh credentials when the current token can no longer open one. */
    private readonly remint: () => Promise<LiveCredentials | null>,
  ) {}

  connect(credentials: LiveCredentials, resumeHandle: string | null = null): void {
    this.credentials = credentials
    this.resumptionHandle = resumeHandle ?? this.resumptionHandle
    this.closedByUs = false
    this.open()
  }

  private open(): void {
    const creds = this.credentials
    if (!creds) return

    this.onConnectionState(this.attempt === 0 ? { state: 'connecting' } : { state: 'reconnecting', attempt: this.attempt })

    const socket = new WebSocket(creds.ws_url)
    socket.binaryType = 'arraybuffer'
    this.socket = socket

    socket.onopen = () => {
      socket.send(this.buildSetupFrame(creds, this.resumptionHandle))
      log.i(TAG, 'socket open, setup sent')
    }

    socket.onmessage = (event: MessageEvent<string | ArrayBuffer | Blob>) => {
      const data = event.data
      if (typeof data === 'string') this.handle(data)
      else if (data instanceof ArrayBuffer) this.handle(new TextDecoder().decode(data))
      else void data.text().then((text) => this.handle(text))
    }

    socket.onerror = () => {
      // The browser deliberately hides the reason; `onclose` carries the code.
      log.w(TAG, 'socket error')
    }

    socket.onclose = (event) => {
      this.socket = null
      if (this.closedByUs) {
        this.onConnectionState({ state: 'closed' })
        return
      }
      // 1006/1008 after an expired token look identical here, so recovery is
      // decided by the token's own deadlines rather than the close code.
      log.i(TAG, `socket closed code=${event.code} reason=${event.reason} - resuming`)
      void this.reconnectSoon()
    }
  }

  /**
   * The first frame: `creds.setup` verbatim.
   *
   * It has no `config` wrapper — that is an SDK abstraction, and sending it is
   * rejected with `Unknown name "config" at 'setup': Cannot find field.` The
   * only thing the client adds is the resumption handle, which is client-side
   * state the server cannot know.
   */
  buildSetupFrame(creds: LiveCredentials, handle: string | null): string {
    const setup: Record<string, unknown> = {}
    for (const [key, value] of Object.entries(creds.setup)) {
      if (key !== 'sessionResumption') setup[key] = value
    }
    setup.sessionResumption = handle ? { handle } : {}
    return JSON.stringify({ setup })
  }

  private handle(payload: string): void {
    let message: Record<string, any>
    try {
      message = JSON.parse(payload)
    } catch (cause) {
      log.w(TAG, 'unparseable server message', cause)
      return
    }

    if (message.setupComplete) {
      this.attempt = 0
      this.onConnectionState({ state: 'connected' })
      this.onEvent({ type: 'ready' })
      log.i(TAG, 'setup complete, session live')
    }

    const content = message.serverContent
    if (content) {
      // Interruption first: dropping queued audio promptly is what makes
      // barge-in feel immediate.
      if (content.interrupted === true) this.onEvent({ type: 'interrupted' })

      // Interim first; the finalised transcription supersedes it a moment later.
      const interim = content.interimInputTranscription?.text
      if (interim) this.onEvent({ type: 'interimInputTranscript', text: interim })
      const input = content.inputTranscription?.text
      if (input) this.onEvent({ type: 'inputTranscript', text: input })
      const output = content.outputTranscription?.text
      if (output) this.onEvent({ type: 'outputTranscript', text: output })

      const grounding = content.groundingMetadata
      if (grounding) {
        const seen = new Set<string>()
        const sources: GroundingSource[] = []
        for (const chunk of grounding.groundingChunks ?? []) {
          const uri = chunk?.web?.uri
          if (uri && !seen.has(uri)) {
            seen.add(uri)
            sources.push({ title: chunk.web.title ?? null, uri })
          }
        }
        const queries: string[] = grounding.webSearchQueries ?? []
        if (sources.length > 0 || queries.length > 0) {
          this.onEvent({ type: 'grounding', queries, sources })
        }
      }

      for (const part of content.modelTurn?.parts ?? []) {
        const data = part?.inlineData?.data
        if (data) {
          try {
            const bytes = base64ToBytes(data)
            // The payload is 16-bit little-endian, which is the platform byte
            // order everywhere this runs; a plain view avoids a copy.
            this.onEvent({
              type: 'audio',
              pcm: new Int16Array(bytes.buffer, bytes.byteOffset, bytes.byteLength >> 1),
            })
          } catch {
            log.w(TAG, 'bad audio payload')
          }
        }
        // Text alongside audio is surfaced as transcript rather than dropped.
        if (part?.text) this.onEvent({ type: 'outputTranscript', text: part.text })
      }

      if (content.generationComplete === true) this.onEvent({ type: 'generationComplete' })
      if (content.turnComplete === true) this.onEvent({ type: 'turnComplete' })
    }

    const functionCalls = message.toolCall?.functionCalls
    if (functionCalls?.length) {
      const calls: ToolCallRequest[] = functionCalls.map((call: any) => ({
        id: call.id ?? null,
        name: call.name,
        args: call.args ?? {},
      }))
      log.i(TAG, `tool call: ${calls.map((c) => c.name).join(', ')}`)
      this.onEvent({ type: 'toolCalls', calls })
    }

    const cancelled = message.toolCallCancellation?.ids
    if (cancelled?.length) this.onEvent({ type: 'toolCallsCancelled', ids: cancelled })

    const update = message.sessionResumptionUpdate
    if (update?.newHandle && update.resumable !== false) {
      this.resumptionHandle = update.newHandle
      this.onEvent({ type: 'resumptionHandle', handle: update.newHandle })
    }

    if (message.goAway) {
      const left = parseDurationMillis(message.goAway.timeLeft)
      log.i(TAG, `server goAway, timeLeft=${left}ms - will resume`)
      this.onEvent({ type: 'goingAway', timeLeftMs: left })
      // Reconnect ahead of the cut so the conversation does not gap.
      void this.reconnectSoon(computeProactiveDelay(left))
    }

    const usage = message.usageMetadata?.totalTokenCount
    if (typeof usage === 'number') this.onEvent({ type: 'usage', totalTokens: usage })
  }

  // ---------------------------------------------------------------------
  // Send
  // ---------------------------------------------------------------------
  sendAudio(pcm: Int16Array): void {
    const mime = this.credentials?.input_audio_mime ?? 'audio/pcm;rate=16000'
    const bytes = new Uint8Array(pcm.buffer, pcm.byteOffset, pcm.byteLength)
    this.send({ realtimeInput: { audio: { data: bytesToBase64(bytes), mimeType: mime } } })
  }

  sendImage(jpeg: Uint8Array): void {
    this.send({ realtimeInput: { video: { data: bytesToBase64(jpeg), mimeType: 'image/jpeg' } } })
    log.d(TAG, `sent camera frame bytes=${jpeg.length}`)
  }

  sendText(text: string): void {
    if (!text.trim()) return
    this.send({ realtimeInput: { text } })
  }

  /**
   * Seed a fresh conversation with context.
   *
   * `requestReply` maps to `turnComplete`. Left false, the context is added
   * silently, which is what any mid-session update wants — true interrupts
   * whatever is generating. It is set true exactly once, at session open, to
   * make the assistant greet first: a voice-first product that waits to be
   * spoken to feels inert, and it is also what stops it opening with "where
   * are you fishing?" when the answer was in the context all along.
   */
  primeContext(turns: { role: string; text: string }[], requestReply = false): void {
    if (turns.length === 0) return
    this.send({
      clientContent: {
        turns: turns.map((turn) => ({ role: turn.role, parts: [{ text: turn.text }] })),
        turnComplete: requestReply,
      },
    })
    log.i(TAG, `primed context with ${turns.length} turn(s) reply=${requestReply}`)
  }

  sendToolResponses(responses: ToolResult[]): void {
    if (responses.length === 0) return
    this.send({
      toolResponse: {
        functionResponses: responses.map((r) => ({
          id: r.id ?? undefined,
          name: r.name,
          response: r.response,
        })),
      },
    })
    log.i(TAG, `sent ${responses.length} tool response(s): ${responses.map((r) => r.name).join(', ')}`)
  }

  private send(frame: unknown): void {
    const socket = this.socket
    if (!socket || socket.readyState !== WebSocket.OPEN) return
    socket.send(JSON.stringify(frame))
  }

  // ---------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------
  private async reconnectSoon(delayMs?: number): Promise<void> {
    if (this.closedByUs) return
    if (this.reconnectTimer !== null) return

    const creds = this.credentials
    if (!creds) return

    // Re-mint rather than retry when the token can no longer open a socket.
    // Checking only `expires_at` here made any drop longer than a minute
    // unrecoverable — see `canStartNewSession`.
    if (isExpired(creds) || !canStartNewSession(creds)) {
      log.i(TAG, `token cannot open a new session (expired=${isExpired(creds)}); re-minting`)
      const fresh = await this.remint()
      if (!fresh) {
        this.onConnectionState({ state: 'failed', code: 'token_expired', message: 'Live credentials expired.' })
        this.onEvent({ type: 'error', code: 'token_expired', message: 'Live credentials expired.', fatal: true })
        return
      }
      this.credentials = fresh
    }

    if (this.attempt >= MAX_RECONNECT_ATTEMPTS) {
      this.onConnectionState({ state: 'failed', code: 'unreachable', message: 'Lost the connection to Gemini.' })
      this.onEvent({ type: 'error', code: 'unreachable', message: 'Lost the connection to Gemini.', fatal: true })
      return
    }

    this.attempt++
    const wait = delayMs ?? Math.min(BASE_RECONNECT_DELAY_MS * 2 ** (this.attempt - 1), 8_000)
    this.onConnectionState({ state: 'reconnecting', attempt: this.attempt })
    this.reconnectTimer = window.setTimeout(() => {
      this.reconnectTimer = null
      this.open()
    }, wait)
  }

  disconnect(): void {
    this.closedByUs = true
    if (this.reconnectTimer !== null) window.clearTimeout(this.reconnectTimer)
    this.reconnectTimer = null
    this.socket?.close(1000, 'client closed')
    this.socket = null
    this.onConnectionState({ state: 'closed' })
  }

  /** Kept across reconnects so a resumed session continues the conversation. */
  get resumeHandle(): string | null {
    return this.resumptionHandle
  }
}

/** `goAway.timeLeft` arrives as a protobuf duration string such as `"9.5s"`. */
export function parseDurationMillis(value: unknown): number | null {
  if (typeof value !== 'string') return null
  const match = /^([0-9]*\.?[0-9]+)s$/.exec(value.trim())
  if (!match?.[1]) return null
  return Math.round(Number.parseFloat(match[1]) * 1000)
}

/**
 * Reconnect a little before the server cuts us off, with a floor so a tiny
 * `timeLeft` does not cause an immediate reconnect storm.
 */
export function computeProactiveDelay(timeLeftMs: number | null): number {
  if (timeLeftMs === null) return 1_000
  return Math.max(250, timeLeftMs - 1_500)
}
