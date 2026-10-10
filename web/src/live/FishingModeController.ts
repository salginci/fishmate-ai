import { AudioCapture } from '../audio/AudioCapture'
import { AudioPlayer } from '../audio/AudioPlayer'
import { CameraSource, DEFAULT_FRAME_CONFIG, type CameraFrame } from '../camera/CameraSource'
import { api } from '../data/api'
import { log } from '../core/log'
import type { LiveCredentials } from '../data/types'
import { LiveSession } from './LiveSession'
import type { GroundingSource, LiveConnectionState, LiveEvent, ToolCallRequest, ToolResult } from './events'
import {
  appendTranscript,
  applyTranslation,
  RepeatCounter,
  type TranscriptLine,
} from './transcript'

/** The one tool name the device answers for; see {@link DEVICE_TOOLS}. */
const TOOL_SET_CAMERA = 'set_camera'

/**
 * Tools executed here instead of being posted to the backend.
 *
 * They act on hardware the server cannot reach. The names must match
 * `DEVICE_TOOLS` in `backend/app/tools/declarations.py`, which is where the
 * declaration the model sees is defined; a name in one set and not the other
 * is the bug this pairing exists to make obvious.
 */
const DEVICE_TOOLS = new Set<string>([TOOL_SET_CAMERA])

/**
 * How long a `set_camera` call waits for the first frame.
 *
 * `getUserMedia` plus the first canvas encode is a few hundred milliseconds;
 * this leaves generous room while staying far short of the point where the
 * angler decides the app has frozen.
 */
const CAMERA_FIRST_FRAME_TIMEOUT_MS = 2_500

/** A location prompt the angler ignores must not hold up the connection. */
const GEOLOCATION_TIMEOUT_MS = 4_000

/** A fix from the last five minutes is plenty: they are standing still. */
const GEOLOCATION_MAX_AGE_MS = 300_000

const TAG = 'FishingMode'

/** How long a line must stop changing before it is translated. */
const TRANSLATE_SETTLE_MS = 900

/** Only the visible tail is worth translating. */
const TRANSLATE_TAIL_LINES = 6

export type AssistantPhase =
  | 'idle'
  | 'connecting'
  | 'listening'
  | 'speaking'
  | 'toolCall'
  | 'reconnecting'
  | 'error'

/** A camera frame held for display, with whether the model received it. */
export type PreviewFrame = {
  url: string
  sent: boolean
  capturedAtEpochMs: number
}

export type FishingModeState = {
  phase: AssistantPhase
  connection: LiveConnectionState
  transcript: TranscriptLine[]
  interimTranscript: string
  cameraEnabled: boolean
  isMuted: boolean
  isWatching: boolean
  showTranslation: boolean
  subtitleLanguage: string
  toolActivity: string[]
  grounding: GroundingSource[]
  framesSent: number
  framesSkipped: number
  interruptions: number
  totalTokens: number
  errorMessage: string | null
  previewFrame: PreviewFrame | null
  echoFloor: number
  /** Time from the angler finishing a sentence to the first audio back. */
  replyLatencyMs: number | null
  /** Sentences the assistant said more than once; the echo-loop symptom. */
  repeatedSentences: number
  /** Times playback ran dry, i.e. audible breaks in the speech. */
  underruns: number
}

const INITIAL: FishingModeState = {
  phase: 'idle',
  connection: { state: 'idle' },
  transcript: [],
  interimTranscript: '',
  cameraEnabled: false,
  isMuted: false,
  isWatching: false,
  showTranslation: true,
  subtitleLanguage: 'tr',
  toolActivity: [],
  grounding: [],
  framesSent: 0,
  framesSkipped: 0,
  interruptions: 0,
  totalTokens: 0,
  errorMessage: null,
  previewFrame: null,
  echoFloor: 0,
  replyLatencyMs: null,
  repeatedSentences: 0,
  underruns: 0,
}

/**
 * Owns the microphone, the camera, the tool loop and reconnection, and reduces
 * all of it to one [FishingModeState].
 *
 * The UI knows nothing about WebSockets and the domain knows nothing about
 * Gemini — the same split the Android app uses.
 */
export class FishingModeController {
  private state: FishingModeState = INITIAL
  private readonly listeners = new Set<(state: FishingModeState) => void>()

  private readonly capture = new AudioCapture()
  private readonly player = new AudioPlayer()
  private camera: CameraSource | null = null
  private stopCameraStream: (() => void) | null = null

  private session: LiveSession | null = null
  private credentials: LiveCredentials | null = null
  private language = 'en'
  private voiceName: string | null = null

  private readonly repeats = new RepeatCounter()

  /**
   * Resolves when the camera stream gets its first frame out.
   *
   * The camera is asked to start from a tool call, and that call must not be
   * answered until the model actually has something to look at; see
   * `applyCameraRequest`.
   */
  private firstFrameWaiter: ((sent: boolean) => void) | null = null
  private translateTimer: number | null = null
  private running = false
  private awaitingReplySince: number | null = null

  /**
   * Loudness of the assistant's voice right now, for the avatar.
   *
   * A getter rather than state on purpose: it is read every animation frame,
   * and pushing it through React would re-render the screen sixty times a
   * second for one canvas.
   */
  getOutputLevel = (): number => this.player.getOutputLevel()

  subscribe(listener: (state: FishingModeState) => void): () => void {
    this.listeners.add(listener)
    listener(this.state)
    return () => this.listeners.delete(listener)
  }

  getState(): FishingModeState {
    return this.state
  }

  private update(patch: Partial<FishingModeState>): void {
    this.state = { ...this.state, ...patch }
    for (const listener of this.listeners) listener(this.state)
  }

  // ---------------------------------------------------------------------
  // Lifecycle
  // ---------------------------------------------------------------------
  async start(language: string, cameraEnabled: boolean, voiceName: string | null = null): Promise<void> {
    if (this.running) return
    this.running = true
    this.language = language
    this.voiceName = voiceName
    this.repeats.reset()

    this.update({
      ...INITIAL,
      phase: 'connecting',
      cameraEnabled,
      // The subtitle is the *other* language: translating into the one being
      // spoken would duplicate every line.
      subtitleLanguage: language.startsWith('tr') ? 'en' : 'tr',
    })

    // Open the audio context first, while the click that got us here still
    // counts as user activation. Creating it after the token round-trip can
    // leave it suspended under the browser's autoplay policy, and a suspended
    // context plays nothing while reporting no error at all.
    this.player.start((playing) => {
      // The echo gate reads the player directly rather than this callback, so
      // it never lags a render behind.
      if (!playing && this.state.phase === 'speaking') this.update({ phase: 'listening' })
    })

    // The position goes with the token request so the backend can name the
    // spot, which is what lets the assistant open with "so you're at Bostanlı"
    // instead of asking a question the browser could answer.
    const position = await this.devicePosition()
    const issued = await api.liveSession(language, cameraEnabled, this.voiceName, position)
    if (!issued.ok) {
      this.update({ phase: 'error', errorMessage: issued.error.message })
      this.player.stop()
      this.running = false
      return
    }
    this.credentials = issued.value

    this.session = new LiveSession(
      (event) => void this.onEvent(event),
      (connection) => this.onConnection(connection),
      async () => {
        const fresh = await api.liveSession(
          this.language,
          this.state.cameraEnabled,
          this.voiceName,
          await this.devicePosition(),
        )
        if (!fresh.ok) return null
        this.credentials = fresh.value
        return fresh.value
      },
    )
    this.session.connect(this.credentials)

    if (cameraEnabled) await this.enableCamera()
  }

  async stop(): Promise<void> {
    if (!this.running) return
    this.running = false

    if (this.translateTimer !== null) window.clearTimeout(this.translateTimer)
    this.translateTimer = null

    this.stopCameraStream?.()
    this.stopCameraStream = null
    this.camera?.release()
    this.camera = null

    this.capture.stop()
    this.player.stop()
    this.session?.disconnect()
    this.session = null

    this.revokePreview()
    this.update({ phase: 'idle', isWatching: false, previewFrame: null })
    log.i(TAG, 'fishing mode stopped')
  }

  // ---------------------------------------------------------------------
  // Controls
  // ---------------------------------------------------------------------
  setMuted(muted: boolean): void {
    this.capture.muted = muted
    this.update({ isMuted: muted })
  }

  toggleTranslation(): void {
    this.update({ showTranslation: !this.state.showTranslation })
  }

  async toggleCamera(): Promise<void> {
    if (this.state.cameraEnabled) {
      this.stopCameraStream?.()
      this.stopCameraStream = null
      this.camera?.release()
      this.camera = null
      this.revokePreview()
      this.update({ cameraEnabled: false, isWatching: false, previewFrame: null })
      return
    }
    this.update({ cameraEnabled: true })
    await this.enableCamera()
  }

  async switchCamera(): Promise<void> {
    await this.camera?.switchCamera()
  }

  /** One-shot capture: far cheaper than streaming when the angler just wants a look. */
  async captureAndSend(): Promise<void> {
    if (!this.camera) {
      this.camera = new CameraSource()
      try {
        await this.camera.start()
      } catch {
        this.update({ errorMessage: 'Camera access lets me analyze your lure, fish, and surroundings.' })
        this.camera = null
        return
      }
    }
    const frame = await this.camera.captureStill()
    if (!frame) return
    this.session?.sendImage(frame.jpeg)
    this.publishPreview(frame, true)
    this.update({ framesSent: this.state.framesSent + 1 })
  }

  sendText(text: string): void {
    this.session?.sendText(text)
  }

  private async enableCamera(): Promise<void> {
    this.camera = new CameraSource()
    try {
      await this.camera.start()
    } catch {
      this.camera = null
      this.update({
        cameraEnabled: false,
        errorMessage: 'Camera access lets me analyze your lure, fish, and surroundings.',
      })
      // A tool call waiting on a first frame will never get one.
      this.firstFrameWaiter?.(false)
      return
    }

    this.stopCameraStream = this.camera.stream_(DEFAULT_FRAME_CONFIG, (frame) => {
      // Adaptive: skip frames while the model is speaking or a tool is
      // running. It is not looking at the camera then, and over a long session
      // those frames are pure cost in tokens, bandwidth and battery.
      // A pending waiter means the model turned the camera on and is waiting
      // to see something. It is mid-tool-call by definition, so the skip below
      // would starve it forever.
      const awaited = this.firstFrameWaiter
      const phase = this.state.phase
      if (!awaited && (phase === 'speaking' || phase === 'toolCall' || phase === 'reconnecting')) {
        this.publishPreview(frame, false)
        this.update({ framesSkipped: this.state.framesSkipped + 1 })
        return
      }
      this.session?.sendImage(frame.jpeg)
      this.publishPreview(frame, true)
      this.update({ isWatching: true, framesSent: this.state.framesSent + 1 })
      awaited?.(true)
    })
  }


  // ---------------------------------------------------------------------
  // Device tools
  // ---------------------------------------------------------------------
  private async executeDeviceTool(call: ToolCallRequest): Promise<ToolResult> {
    if (call.name !== TOOL_SET_CAMERA) {
      // Unreachable unless DEVICE_TOOLS and this dispatch drift apart.
      log.e(TAG, `no device handler for ${call.name}`)
      return this.deviceToolError(
        call,
        `'${call.name}' is not something this device can do.`,
        'Say you cannot do that right now, in one short sentence, and do not retry.',
      )
    }
    const enabled = call.args?.['enabled']
    if (typeof enabled !== 'boolean') {
      log.w(TAG, "set_camera called without a usable 'enabled'")
      return this.deviceToolError(
        call,
        "set_camera needs 'enabled' to be true or false.",
        'Ask the user whether you should look, in one short sentence.',
      )
    }
    return this.applyCameraRequest(call, enabled)
  }

  /**
   * Turn the camera on or off because the model asked.
   *
   * This is what makes "do you see this?" work without the angler touching
   * anything. Two details matter and both were learned the hard way.
   *
   * The result is returned only **after** a frame has actually gone out, so
   * the model is looking at something by the time it speaks. Returning
   * immediately makes it answer about a camera that has not produced a frame
   * yet, and it either stalls or invents.
   *
   * And a failure must come back as a failure. A model told "camera on" that
   * then sees nothing will describe a fish it cannot see, which is the one
   * thing this product must never do.
   *
   * Mirrors `applyCameraRequest` in `FishingModeController.kt`.
   */
  private async applyCameraRequest(call: ToolCallRequest, enabled: boolean): Promise<ToolResult> {
    if (!enabled) {
      if (this.state.cameraEnabled) await this.toggleCamera()
      return {
        id: call.id,
        name: call.name,
        response: {
          camera: 'off',
          instruction_to_model:
            'The camera is off and you can no longer see. Do not describe anything ' +
            'visual from now on. Say nothing about this unless the user asks.',
        },
      }
    }

    const alreadyWatching = this.state.cameraEnabled && this.state.framesSent > 0
    const arrived = alreadyWatching ? true : await this.awaitFirstFrame()
    if (!arrived) {
      if (this.state.cameraEnabled) await this.toggleCamera()
      return this.deviceToolError(
        call,
        this.state.errorMessage ?? 'The camera did not produce an image.',
        'Tell the user in one short sentence that you cannot see right now, then keep ' +
          'helping with what they told you. Do not describe anything visual and do ' +
          'not retry.',
      )
    }
    return {
      id: call.id,
      name: call.name,
      response: {
        camera: 'on',
        instruction_to_model:
          'You can see now, and a frame has already been sent. Describe what is ' +
          'actually in it, separating what you observe from what you infer. If it is ' +
          'too dark or blurred to judge, say so and ask for a better angle rather ' +
          'than guessing. Call set_camera with enabled=false when you have finished ' +
          'looking.',
      },
    }
  }

  /**
   * Start the stream and resolve once one frame has gone to the model.
   *
   * The timeout is not optional: a camera that never starts — permission just
   * revoked, device grabbed by another tab — would otherwise leave the tool
   * call unanswered, and an unanswered tool call hangs the conversation for
   * good.
   */
  private async awaitFirstFrame(): Promise<boolean> {
    const arrived = new Promise<boolean>((resolve) => {
      this.firstFrameWaiter = resolve
    })
    const timed = new Promise<boolean>((resolve) => {
      window.setTimeout(() => resolve(false), CAMERA_FIRST_FRAME_TIMEOUT_MS)
    })
    this.update({ cameraEnabled: true })
    void this.enableCamera()
    try {
      const got = await Promise.race([arrived, timed])
      if (!got) log.w(TAG, `camera produced no frame within ${CAMERA_FIRST_FRAME_TIMEOUT_MS}ms`)
      return got
    } finally {
      this.firstFrameWaiter = null
    }
  }

  private deviceToolError(call: ToolCallRequest, message: string, instruction: string): ToolResult {
    return {
      id: call.id,
      name: call.name,
      response: {
        error: 'device_tool_failed',
        message,
        instruction_to_model: instruction,
      },
    }
  }

  /**
   * Publish the frame for the on-screen preview.
   *
   * The preview shows the exact JPEG that went to the model rather than a
   * second, prettier camera stream, so "what is it looking at?" has an honest
   * answer — framing, resolution and staleness included.
   */
  private publishPreview(frame: CameraFrame, sent: boolean): void {
    this.revokePreview()
    const blob = new Blob([frame.jpeg as unknown as BlobPart], { type: 'image/jpeg' })
    this.update({
      previewFrame: { url: URL.createObjectURL(blob), sent, capturedAtEpochMs: frame.capturedAtEpochMs },
    })
  }

  private revokePreview(): void {
    const previous = this.state.previewFrame
    if (previous) URL.revokeObjectURL(previous.url)
  }

  // ---------------------------------------------------------------------
  // Events
  // ---------------------------------------------------------------------
  private onConnection(connection: LiveConnectionState): void {
    const patch: Partial<FishingModeState> = { connection }
    if (connection.state === 'reconnecting') {
      patch.phase = 'reconnecting'
      // Audio queued before the drop is stale by the time we are back.
      this.player.flush()
    }
    if (connection.state === 'failed') {
      patch.phase = 'error'
      patch.errorMessage = connection.message
    }
    this.update(patch)
  }

  private async onEvent(event: LiveEvent): Promise<void> {
    switch (event.type) {
      case 'ready': {
        this.update({ phase: 'listening', errorMessage: null })
        if (this.state.transcript.length === 0) this.primeContext()
        // Only start sending media once setup is accepted; frames sent before
        // that are discarded by the server.
        await this.startMicrophone()
        break
      }

      case 'audio': {
        this.player.enqueue(event.pcm)
        const started = this.awaitingReplySince
        this.awaitingReplySince = null
        this.update({
          phase: 'speaking',
          underruns: this.player.underruns,
          ...(started !== null ? { replyLatencyMs: Date.now() - started } : {}),
        })
        break
      }

      case 'interrupted':
        // Barge-in: drop everything queued so the angler is heard at once.
        this.player.flush()
        this.update({ phase: 'listening', interruptions: this.state.interruptions + 1 })
        log.d(TAG, 'model interrupted by user')
        break

      case 'interimInputTranscript':
        this.update({ interimTranscript: event.text })
        break

      case 'inputTranscript':
        // Clock starts here: this is the moment the angler stopped talking.
        this.awaitingReplySince = Date.now()
        this.update({
          transcript: appendTranscript(this.state.transcript, 'user', event.text),
          interimTranscript: '',
        })
        this.scheduleTranslation()
        break

      case 'outputTranscript': {
        const transcript = appendTranscript(this.state.transcript, 'assistant', event.text)
        const line = transcript[transcript.length - 1]
        this.update({
          transcript,
          repeatedSentences: line ? this.repeats.observe(line) : this.repeats.total,
        })
        this.scheduleTranslation()
        break
      }

      case 'toolCalls': {
        this.update({
          phase: 'toolCall',
          toolActivity: [...this.state.toolActivity, ...event.calls.map((c) => c.name)].slice(-8),
        })

        // Some tools act on hardware the backend cannot reach, so they are
        // executed here and never posted. See DEVICE_TOOLS.
        const deviceCalls = event.calls.filter((call) => DEVICE_TOOLS.has(call.name))
        const backendCalls = event.calls.filter((call) => !DEVICE_TOOLS.has(call.name))

        const deviceResults: ToolResult[] = []
        for (const call of deviceCalls) deviceResults.push(await this.executeDeviceTool(call))

        if (backendCalls.length === 0) {
          this.session?.sendToolResponses(deviceResults)
          this.update({ phase: 'listening' })
          this.scheduleTranslation()
          break
        }

        const results = await api.executeTools(
          backendCalls.map((call) => ({
            name: call.name,
            call_id: call.id,
            args: call.args,
            language: this.language,
          })),
        )
        if (!results.ok) {
          // The model must still get a response, or it waits forever. An
          // explicit failure is far better than silence — but only for the
          // calls that actually went to the backend; the device ones already
          // have real answers.
          this.session?.sendToolResponses([
            ...deviceResults,
            ...backendCalls.map((call) => ({
              id: call.id,
              name: call.name,
              response: { error: results.error.message },
            })),
          ])
          this.update({ phase: 'listening' })
          break
        }
        this.session?.sendToolResponses([
          ...deviceResults,
          ...results.value.results.map((result) => ({
            id: result.call_id,
            name: result.name,
            response: result.response,
          })),
        ])
        this.update({ phase: 'listening' })
        break
      }

      case 'grounding':
        this.update({ grounding: event.sources })
        break

      case 'usage':
        this.update({ totalTokens: event.totalTokens })
        break

      case 'error':
        this.update({ phase: 'error', errorMessage: event.message })
        break

      case 'turnComplete':
        if (this.state.phase === 'speaking') this.update({ phase: 'listening' })
        break

      default:
        break
    }
  }

  /**
   * Where the browser thinks it is, or null.
   *
   * Never fatal and never blocking for long: a denied or slow permission just
   * means the session opens without a place name, and a prompt the angler
   * ignores must not hold up the connection.
   */
  private async devicePosition(): Promise<{ latitude: number; longitude: number } | null> {
    if (!navigator.geolocation) return null
    return new Promise((resolve) => {
      navigator.geolocation.getCurrentPosition(
        (p) => resolve({ latitude: p.coords.latitude, longitude: p.coords.longitude }),
        () => resolve(null),
        { timeout: GEOLOCATION_TIMEOUT_MS, maximumAge: GEOLOCATION_MAX_AGE_MS },
      )
    })
  }

  /**
   * Open the session knowing the spot, the clock and the angler.
   *
   * Mirrors `primeFishingContext` in `FishingModeController.kt`, including the
   * part that matters: the second turn is a directive, sent with
   * `requestReply`, so the assistant speaks first instead of waiting and then
   * asking where the angler is.
   */
  private primeContext(): void {
    const brief = this.credentials?.user_brief ?? {}
    const turkish = this.language.startsWith('tr')

    // The clock comes from the device, not the backend: the browser is at the
    // spot and already knows the local time.
    const now = new Date()
    const locale = turkish ? 'tr-TR' : 'en-GB'
    const clock = now.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' })
    const day = now.toLocaleDateString(locale, { weekday: 'long' })

    const facts: string[] = [turkish ? '[Oturum bağlamı]' : '[Session context]']
    facts.push(
      turkish
        ? `Şu an ${day}, saat ${clock} (balıkçının cihazındaki yerel saat).`
        : `It is ${day}, ${clock} local time on the angler's device.`,
    )

    const place = brief.place as { name?: string; region?: string } | undefined
    if (place?.name) {
      const where = place.region && place.region !== place.name ? `${place.name} (${place.region})` : place.name
      facts.push(turkish ? `Balıkçı şu anda ${where} civarında.` : `The angler is at ${where} right now.`)
    }
    if (typeof brief.display_name === 'string') {
      facts.push(turkish ? `Adı ${brief.display_name}.` : `Their name is ${brief.display_name}.`)
    }
    if (typeof brief.total_sessions === 'number') {
      facts.push(
        turkish
          ? `Kayıtlı ${brief.total_sessions} oturumu var.`
          : `They have logged ${brief.total_sessions} sessions.`,
      )
    }
    if (typeof brief.total_catches === 'number') {
      facts.push(
        turkish
          ? `${brief.total_catches} av kaydı var.`
          : `They have recorded ${brief.total_catches} catches.`,
      )
    }
    facts.push(
      turkish
        ? 'Detay için araçları kullan; bu özeti kullanıcıya okuma.'
        : 'Use the tools for detail; never read this summary out.',
    )

    // A separate turn from the facts, so the model treats it as an instruction
    // rather than as something to recite.
    const directive = turkish
      ? '[Başla] Şimdi sen konuşmaya başla. Nerede olduğunu sorma — yukarıda yazıyor. ' +
        'Yeri ve saati kullanarak selam ver, get_current_conditions çağırıp kısa bir tavır al, ' +
        'sonra suyu göstermesini iste. En fazla iki üç cümle.'
      : '[Begin] Speak first, now. Do not ask where they are — it is above. Greet them using ' +
        'the spot and the time, call get_current_conditions and give a short opinion, then ask ' +
        'to see the water. Two or three sentences at most.'

    this.session?.primeContext(
      [
        { role: 'user', text: facts.join(' ') },
        { role: 'user', text: directive },
      ],
      true,
    )
  }

  private async startMicrophone(): Promise<void> {
    try {
      await this.capture.start({
        onFrame: (pcm) => this.session?.sendAudio(pcm),
        // Read from the player rather than from state: the echo gate must know
        // whether sound is leaving the speaker *now*, not one render later.
        isSpeaking: () => this.player.playing,
      })
    } catch {
      this.update({
        phase: 'error',
        errorMessage: 'Microphone access is required for Fishing Mode.',
      })
    }
  }

  /**
   * Translate transcript lines once they settle.
   *
   * Live transcription arrives as fragments that change several times a
   * second, so the debounce turns one request per fragment into roughly one
   * per conversational turn.
   */
  private scheduleTranslation(): void {
    if (!this.state.showTranslation) return
    if (this.translateTimer !== null) window.clearTimeout(this.translateTimer)
    this.translateTimer = window.setTimeout(() => {
      this.translateTimer = null
      void this.translateTail()
    }, TRANSLATE_SETTLE_MS)
  }

  private async translateTail(): Promise<void> {
    const target = this.state.subtitleLanguage
    const pending = this.state.transcript
      .slice(-TRANSLATE_TAIL_LINES)
      .filter((line) => line.translation === null && line.text.trim().length > 0)

    for (const line of pending) {
      const result = await api.translate(line.text, target)
      if (!result.ok) {
        // A missing subtitle is cosmetic; the conversation is unaffected.
        log.d(TAG, `subtitle unavailable: ${result.error.message}`)
        continue
      }
      this.update({
        transcript: applyTranslation(this.state.transcript, line.id, line.text, result.value.text),
      })
    }
    this.update({ echoFloor: this.capture.echoFloor })
  }
}
