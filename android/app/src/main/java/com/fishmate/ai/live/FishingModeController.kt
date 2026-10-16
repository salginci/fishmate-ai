package com.fishmate.ai.live

import com.fishmate.ai.audio.AudioCapture
import com.fishmate.ai.audio.AudioCaptureError
import com.fishmate.ai.audio.AudioPlayer
import com.fishmate.ai.audio.AudioRoute
import com.fishmate.ai.audio.AudioRouting
import com.fishmate.ai.camera.CameraFacing
import com.fishmate.ai.camera.CameraSource
import com.fishmate.ai.camera.FrameConfig
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.repository.LiveCredentialsRepository
import com.fishmate.ai.location.LocationProvider
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The state machine behind Fishing Mode.
 *
 * This is the piece that makes Gemini Live *the interaction layer* rather than
 * a feature: it owns the microphone, the camera, the tool loop and the
 * reconnect policy, and reduces all of it to one [FishingModeState] the UI can
 * render without knowing any of it.
 *
 * Deliberately free of Android UI types so the whole conversation flow —
 * including interruption, tool calls and resumption — is unit-testable against
 * [MockLiveAiSession].
 */
class FishingModeController(
    private val session: LiveAiSession,
    private val credentials: LiveCredentialsRepository,
    private val toolOrchestrator: ToolOrchestrator,
    private val audioCapture: AudioCapture,
    private val audioPlayer: AudioPlayer,
    private val audioRouting: AudioRouting,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
    private val cameraSourceProvider: () -> CameraSource?,
) {
    private val _previewFrame = MutableStateFlow<CameraPreviewFrame?>(null)

    /**
     * The most recent camera frame, for the on-screen preview.
     *
     * Kept out of [state] so a 20 KB JPEG does not ride along with every
     * transcript update. The preview shows the exact frames the camera
     * produced for the model — not a second, prettier preview stream — so
     * "what is it looking at?" has an honest answer, with [CameraPreviewFrame.sent]
     * distinguishing frames that actually went to Gemini from ones skipped
     * while it was speaking.
     */
    val previewFrame: StateFlow<CameraPreviewFrame?> = _previewFrame.asStateFlow()

    private val _state = MutableStateFlow(FishingModeState())
    val state: StateFlow<FishingModeState> = _state.asStateFlow()

    /**
     * Loudness of the model's voice leaving the speaker right now, 0 to 1.
     *
     * Deliberately not part of [state]: the avatar samples it every animation
     * frame, and putting it in the state flow would re-emit the whole screen's
     * worth of data sixty times a second.
     */
    val outputLevel: Float get() = audioPlayer.outputLevel

    /** Correlates device-side turns with the backend's tool audit rows. */
    private val liveSessionRef = UUID.randomUUID().toString().take(16)

    private var eventsJob: Job? = null

    private val repeats = RepeatCounter()

    /**
     * Completed by the camera stream when the first frame goes out.
     *
     * The camera is asked to start from a tool call, and that call must not be
     * answered until the model actually has something to look at; see
     * [applyCameraRequest].
     */
    @Volatile
    private var firstFrameWaiter: CompletableDeferred<Boolean>? = null

    /**
     * Completed when the opening greeting becomes audible.
     *
     * The microphone waits on this. Server VAD is configured with
     * START_OF_ACTIVITY_INTERRUPTS because barge-in is a product requirement,
     * and that cuts both ways: with the mic already streaming, a gust of wind
     * or a car going past in the second between priming and the first audio
     * chunk counts as the angler speaking, cancels the turn that was being
     * generated, and the session settles into "Listening" having never said
     * hello. Waiting for the greeting to *start* -- not finish -- closes that
     * window while leaving every later interruption intact.
     */
    @Volatile
    private var greetingWaiter: CompletableDeferred<Unit>? = null
    /** Waits out the speaker after the server says it has finished generating. */
    private var drainJob: Job? = null
    private var micJob: Job? = null
    private var cameraJob: Job? = null
    private var echoGuardJob: Job? = null
    private var routeJob: Job? = null
    private var replyWatchdog: Job? = null

    /** Open while a renewal is too recent to repeat; see [renewCredentialsAndReconnect]. */
    private var renewGuard: Job? = null
    private var connectionJob: Job? = null
    private var language: String = "en"

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------
    suspend fun start(language: String, cameraEnabled: Boolean) {
        this.language = language
        repeats.reset()
        _state.update {
            it.copy(
                phase = AssistantPhase.CONNECTING,
                cameraEnabled = cameraEnabled,
                errorMessage = null,
            )
        }

        if (!audioRouting.acquireFocus()) {
            // Another app owns the audio (a call). Starting would mean the
            // angler neither hears nor is heard.
            _state.update {
                it.copy(
                    phase = AssistantPhase.ERROR,
                    errorMessage = "Another app is using the audio right now.",
                )
            }
            return
        }
        audioRouting.startRouteMonitoring()
        // Hands-free from the first second: VOICE_COMMUNICATION would
        // otherwise route to the earpiece and the angler would have to
        // hold the phone to their ear.
        audioRouting.applyHandsFreeRouting()

        // The position goes with the token request so the backend can name the
        // spot, which is what lets the assistant open with "so you're at
        // Bostanlı" instead of asking a question the phone could answer.
        val position = locationProvider.lastKnown()
        val issued = credentials.current(
            language,
            expectsVideo = cameraEnabled,
            latitude = position?.latitude,
            longitude = position?.longitude,
        )
        if (issued is AppResult.Err) {
            _state.update {
                it.copy(phase = AssistantPhase.ERROR, errorMessage = issued.error.message)
            }
            audioRouting.clearRouting()
            audioRouting.releaseFocus()
            return
        }

        observeEvents()
        observeConnection()
        observeEchoGuard()
        observeRoute()

        audioPlayer.start()
        session.connect((issued as AppResult.Ok).value, resumeHandle = _state.value.resumeHandle)
    }

    suspend fun stop() {
        micJob?.cancel(); micJob = null
        audioCapture.speakerLevel = { 0f }
        audioCapture.playbackFrame = { 0L }
        audioPlayer.onFarEnd = null
        audioPlayer.onPlaybackReset = null
        audioCapture.onBargeIn = {}
        cameraJob?.cancel(); cameraJob = null
        echoGuardJob?.cancel(); echoGuardJob = null
        routeJob?.cancel(); routeJob = null
        replyWatchdog?.cancel(); replyWatchdog = null
        audioCapture.suppressWhileSpeaking = false
        audioPlayer.stop()
        session.disconnect()
        eventsJob?.cancel(); eventsJob = null
        connectionJob?.cancel(); connectionJob = null
        audioRouting.clearRouting()
        audioRouting.stopRouteMonitoring()
        audioRouting.releaseFocus()
        cameraSourceProvider()?.release()
        _previewFrame.value = null
        _state.update { it.copy(phase = AssistantPhase.IDLE, isListening = false) }
        Log2.i(TAG, "fishing mode stopped")
    }

    fun release() {
        session.release()
        audioPlayer.stop()
    }

    /**
     * Attach a subtitle to a transcript line.
     *
     * No-op if the line has since grown (the merge cleared its translation and
     * a newer request is already in flight) or scrolled off the bounded
     * transcript, so a slow response can never show a subtitle that belongs to
     * a shorter version of the sentence.
     */
    fun applyTranslation(lineId: Long, sourceText: String, translation: String) {
        _state.update { current ->
            val index = current.transcript.indexOfFirst { it.id == lineId }
            if (index < 0) return@update current
            val line = current.transcript[index]
            if (line.text != sourceText || line.translation != null) return@update current
            current.copy(
                transcript = current.transcript.toMutableList().also {
                    it[index] = line.copy(translation = translation)
                },
            )
        }
    }

    // -----------------------------------------------------------------------
    // Controls
    // -----------------------------------------------------------------------
    fun setMuted(muted: Boolean) {
        audioCapture.isMuted = muted
        _state.update { it.copy(isMuted = muted) }
        if (muted) {
            // Tell the server the turn ended so it does not wait for more
            // speech that will never arrive.
            scope.launch { session.signalAudioStreamEnd() }
        }
        Log2.i(TAG, "microphone muted=$muted")
    }

    fun setCameraEnabled(enabled: Boolean) {
        _state.update { it.copy(cameraEnabled = enabled) }
        if (enabled) startCameraStream() else {
            cameraJob?.cancel()
            cameraJob = null
            _state.update { it.copy(isWatching = false) }
        }
        Log2.i(TAG, "camera enabled=$enabled")
    }

    fun switchCamera() {
        scope.launch {
            cameraSourceProvider()?.switchCamera()
            if (_state.value.cameraEnabled) {
                // Rebind so the new facing takes effect.
                cameraJob?.cancel()
                startCameraStream()
            }
        }
    }

    fun setFrameRate(framesPerSecond: Double) {
        _state.update { it.copy(frameConfig = it.frameConfig.copy(framesPerSecond = framesPerSecond)) }
        if (_state.value.cameraEnabled) {
            cameraJob?.cancel()
            startCameraStream()
        }
    }

    /** Send one frame now, for "look at this". */
    fun captureAndSendFrame() {
        scope.launch {
            val frame = cameraSourceProvider()?.captureStill(_state.value.frameConfig)
            if (frame != null) {
                session.sendImage(frame.jpeg)
                _previewFrame.value = CameraPreviewFrame(frame.jpeg, sent = true)
                _state.update { it.copy(framesSent = it.framesSent + 1) }
                return@launch
            }
            // `captureStill` hands back the most recent *streamed* frame, so it
            // is null whenever the camera is not already running — which is the
            // normal case for this button, and used to mean it silently did
            // nothing. Start the camera just long enough to get one frame.
            val wasOn = _state.value.cameraEnabled
            if (!awaitLookFrames()) Log2.w(TAG, "look-at-this produced no frame")
            if (!wasOn) setCameraEnabled(false)
        }
    }

    fun sendText(text: String) {
        scope.launch { session.sendText(text) }
        _state.update {
            it.copy(transcript = it.transcript + TranscriptLine(TranscriptSpeaker.USER, text))
        }
    }

    // -----------------------------------------------------------------------
    // Pipelines
    // -----------------------------------------------------------------------
    private fun startMicrophoneStream() {
        if (micJob != null) return
        // The far-end reference the gate needs, and the local fast path that
        // stops playback without waiting for the server to agree.
        audioCapture.speakerLevel = { audioPlayer.echoReference }
        audioCapture.playbackFrame = { audioPlayer.playbackFrame }
        audioPlayer.onFarEnd = { pcm, offset, length, frame ->
            audioCapture.pushFarEnd(pcm, offset, length, frame)
        }
        audioPlayer.onPlaybackReset = { audioCapture.resetEcho() }
        audioCapture.onBargeIn = { onLocalBargeIn() }
        micJob = scope.launch {
            audioCapture.chunks()
                .catch { error ->
                    val message = (error as? AudioCaptureError)?.userMessage
                        ?: "Microphone access is required for Fishing Mode."
                    Log2.w(TAG, "microphone stream ended: $message")
                    _state.update {
                        it.copy(
                            phase = AssistantPhase.ERROR,
                            errorMessage = message,
                            isListening = false,
                        )
                    }
                }
                .collect { chunk ->
                    session.sendAudio(chunk)
                    if (!_state.value.isListening) {
                        _state.update { it.copy(isListening = true) }
                    }
                }
        }
    }

    private fun startCameraStream() {
        val source = cameraSourceProvider() ?: run {
            // No camera attached — permission was refused, or Fishing Mode was
            // started before the screen bound one. Fail any waiting tool call
            // now rather than making it sit out the timeout.
            Log2.w(TAG, "camera requested but no source is attached")
            firstFrameWaiter?.complete(false)
            return
        }
        cameraJob = scope.launch {
            source.frames(_state.value.frameConfig)
                .catch { error ->
                    Log2.w(TAG, "camera stream ended", error)
                    _state.update {
                        it.copy(
                            isWatching = false,
                            cameraEnabled = false,
                            errorMessage = "Camera access lets me analyze your lure, " +
                                "fish, and surroundings.",
                        )
                    }
                    // A tool call waiting on a first frame will never get one.
                    firstFrameWaiter?.complete(false)
                }
                .collect { frame ->
                    // Adaptive: skip frames while the model is speaking or a
                    // tool is running. It is not looking at the camera then,
                    // and on a three-hour session those frames are pure cost
                    // in tokens, bandwidth and battery.
                    // A pending waiter means the model turned the camera on and
                    // is waiting to see something. It is mid-tool-call by
                    // definition, so the skip below would starve it forever.
                    val awaited = firstFrameWaiter
                    val phase = _state.value.phase
                    if (awaited == null && (
                            phase == AssistantPhase.SPEAKING ||
                                phase == AssistantPhase.TOOL_CALL ||
                                phase == AssistantPhase.RECONNECTING
                            )
                    ) {
                        _previewFrame.value = CameraPreviewFrame(frame.jpeg, sent = false)
                        _state.update { it.copy(framesSkipped = it.framesSkipped + 1) }
                        return@collect
                    }
                    session.sendImage(frame.jpeg)
                    _previewFrame.value = CameraPreviewFrame(frame.jpeg, sent = true)
                    _state.update {
                        it.copy(
                            isWatching = true,
                            framesSent = it.framesSent + 1,
                            bytesSentApprox = it.bytesSentApprox + frame.jpeg.size,
                        )
                    }
                    awaited?.complete(true)
                }
        }
    }

    /**
     * Break the acoustic echo loop while the assistant is speaking.
     *
     * On the built-in loudspeaker the microphone hears the model's own voice.
     * Server-side VAD reads that as a barge-in, so the model stops, restarts,
     * hears itself again, and the conversation collapses — observed at ~32
     * interruptions a minute, about one every three seconds. The echo is also
     * fed back as input audio, and language detection run over a distorted
     * copy of its own speech is what made it wander into other languages.
     *
     * Platform echo cancellation (attached in [AudioCapture]) is not enough
     * against a speaker at arm's length. This does **not** close the
     * microphone: it only tells [EchoGate] that an acoustic path exists, so
     * each frame is judged against the playback level instead of forwarded
     * blind. Capture stays open for the whole turn, which is what makes
     * barge-in possible on the loudspeaker.
     *
     * The tail covers the samples still inside the AudioTrack buffer after
     * our queue drains — the speaker is still sounding then, so the gate must
     * still be judging.
     *
     * On a headset there is no acoustic path at all, so the gate is switched
     * off entirely and every frame goes straight through.
     */
    private fun observeEchoGuard() {
        echoGuardJob?.cancel()
        echoGuardJob = scope.launch {
            audioPlayer.playing.collectLatest { speaking ->
                val onSpeaker = audioRouting.route.value != AudioRoute.WIRED_HEADSET &&
                    audioRouting.route.value != AudioRoute.BLUETOOTH
                if (!onSpeaker) {
                    audioCapture.suppressWhileSpeaking = false
                    return@collectLatest
                }
                if (speaking) {
                    audioCapture.suppressWhileSpeaking = true
                } else {
                    // collectLatest cancels this if speech resumes, so the
                    // gate only opens after a real gap.
                    delay(ECHO_TAIL_MS)
                    audioCapture.suppressWhileSpeaking = false
                }
            }
        }
    }

    /**
     * Keep the UI's idea of the output device equal to the system's.
     *
     * Reported rather than remembered: an earbud that runs flat mid-session
     * must not leave a "Bluetooth" label over a loudspeaker.
     */
    private fun observeRoute() {
        routeJob?.cancel()
        routeJob = scope.launch {
            audioRouting.route.collect { route ->
                _state.update { it.copy(audioRoute = route) }
                // Routing follows the device, but only while the angler wants
                // hands-free; AudioRouting re-applies on its own device
                // callbacks and this covers the rest.
                if (_state.value.handsFree) audioRouting.applyHandsFreeRouting()
            }
        }
    }

    /**
     * Turn hands-free output on or off mid-session.
     *
     * Nothing else changes: the microphone stays open, the socket stays up,
     * and the echo gate carries on, because hands-free is about where the
     * voice comes out and not about whether the angler can be heard.
     */
    fun setHandsFree(enabled: Boolean) {
        if (_state.value.handsFree == enabled) return
        _state.update { it.copy(handsFree = enabled) }
        if (enabled) audioRouting.applyHandsFreeRouting() else audioRouting.clearRouting()
        Log2.i(TAG, "hands-free set to $enabled (route=${audioRouting.route.value})")
    }

    private fun observeConnection() {
        connectionJob?.cancel()
        connectionJob = scope.launch {
            session.connectionState.collect { connection ->
                _state.update { current ->
                    current.copy(
                        connection = connection,
                        phase = when (connection) {
                            is LiveConnectionState.Connected ->
                                if (current.phase == AssistantPhase.CONNECTING ||
                                    current.phase == AssistantPhase.RECONNECTING
                                ) {
                                    AssistantPhase.LISTENING
                                } else {
                                    current.phase
                                }
                            is LiveConnectionState.Connecting -> AssistantPhase.CONNECTING
                            is LiveConnectionState.Reconnecting -> AssistantPhase.RECONNECTING
                            is LiveConnectionState.Failed -> AssistantPhase.ERROR
                            is LiveConnectionState.Closed,
                            is LiveConnectionState.Idle,
                            -> AssistantPhase.IDLE
                        },
                        errorMessage = (connection as? LiveConnectionState.Failed)?.message
                            ?: current.errorMessage,
                    )
                }

                // A reconnect means a brand-new socket: playback of the old
                // turn is meaningless, so drop it rather than resume mid-word.
                if (connection is LiveConnectionState.Reconnecting) audioPlayer.flush()

                // A reconnect is a new socket, not a new audio session, but
                // focus can be lost and regained around one. Re-asserting is
                // cheap and stops a resumed conversation coming out of the
                // earpiece with nobody able to explain why.
                if (connection is LiveConnectionState.Connected && _state.value.handsFree) {
                    audioRouting.applyHandsFreeRouting()
                }

                if (connection is LiveConnectionState.Failed &&
                    connection.code == "token_expired"
                ) {
                    renewCredentialsAndReconnect()
                }
            }
        }
    }

    private fun observeEvents() {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            session.events.collect { event -> handle(event) }
        }
    }

    private suspend fun handle(event: LiveEvent) {
        when (event) {
            LiveEvent.Ready -> {
                // Seed the angler's history before the first question, so
                // "what did I use last time?" has context from turn one.
                // Skipped on a resumed session: the context is already there.
                val greeting = _state.value.resumeHandle == null
                _state.update {
                    it.copy(
                        // Not LISTENING: on a fresh session the assistant is
                        // about to talk, and labelling that "Listening — ask
                        // me anything" tells the angler to speak into a turn
                        // that is already being generated.
                        phase = if (greeting) AssistantPhase.THINKING else AssistantPhase.LISTENING,
                        errorMessage = null,
                    )
                }
                if (greeting) {
                    greetingWaiter = CompletableDeferred()
                    primeFishingContext()
                }

                // Only start sending media once the server has accepted setup;
                // frames sent before that are discarded.
                //
                // Launched rather than awaited here: this runs inside the
                // event collector, and the event that releases the greeting
                // wait is the first audio chunk, which arrives through that
                // same collector. Waiting inline would deadlock it until the
                // timeout fired, every single session.
                scope.launch {
                    if (greeting) awaitGreetingStart()
                    startMicrophoneStream()
                    if (_state.value.cameraEnabled) startCameraStream()
                }
            }

            is LiveEvent.AudioChunk -> {
                cancelReplyWatchdog()
                audioPlayer.enqueue(event.pcm)
                greetingWaiter?.complete(Unit)
                drainJob?.cancel()
                drainJob = null
                _state.update { it.copy(phase = AssistantPhase.SPEAKING) }
            }

            LiveEvent.Interrupted -> {
                // Barge-in: drop everything queued so the angler is heard at
                // once. This is the behaviour that makes it feel conversational.
                drainJob?.cancel()
                drainJob = null
                audioPlayer.flush()
                _state.update { it.copy(phase = AssistantPhase.LISTENING, interruptions = it.interruptions + 1) }
                Log2.d(TAG, "model interrupted by user")
            }

            is LiveEvent.InterimInputTranscript -> _state.update {
                // Replaces rather than appends: the finalised InputTranscript
                // supersedes it a moment later.
                it.copy(interimTranscript = event.text)
            }

            is LiveEvent.InputTranscript -> _state.update {
                it.copy(
                    transcript = it.transcript.append(TranscriptSpeaker.USER, event.text),
                    interimTranscript = "",
                )
            }

            is LiveEvent.Grounding -> {
                Log2.i(TAG, "grounded answer with ${event.sources.size} source(s)")
                _state.update {
                    it.copy(
                        groundingSources = event.sources,
                        groundingQueries = event.queries,
                    )
                }
            }

            is LiveEvent.WaitingForInput -> _state.update {
                it.copy(waitingForInput = event.waiting)
            }

            is LiveEvent.OutputTranscript -> {
                cancelReplyWatchdog()
                val transcript = _state.value.transcript
                    .append(TranscriptSpeaker.ASSISTANT, event.text)
                val total = repeats.observe(transcript.last())
                _state.update {
                    it.copy(transcript = transcript, repeatedSentences = total)
                }
            }

            LiveEvent.GenerationComplete, LiveEvent.TurnComplete -> {
                cancelReplyWatchdog()
                awaitPlaybackDrained()
            }

            is LiveEvent.ToolCalls -> {
                cancelReplyWatchdog()
                executeTools(event.calls)
            }

            is LiveEvent.ToolCallsCancelled -> Log2.i(
                TAG,
                "tool calls cancelled: ${event.ids.joinToString()}",
            )

            is LiveEvent.ResumptionHandle -> _state.update {
                // Stored so a drop resumes this conversation instead of
                // starting a new one.
                it.copy(resumeHandle = event.handle)
            }

            is LiveEvent.GoingAway -> {
                Log2.i(TAG, "server rotating connection in ${event.timeLeftMs}ms")
                _state.update { it.copy(phase = AssistantPhase.RECONNECTING) }
            }

            is LiveEvent.Usage -> _state.update {
                it.copy(totalTokens = event.totalTokens ?: it.totalTokens)
            }

            is LiveEvent.Error -> {
                if (event.code == "token_expired") {
                    renewCredentialsAndReconnect()
                } else {
                    _state.update {
                        it.copy(
                            phase = if (event.fatal) AssistantPhase.ERROR else it.phase,
                            errorMessage = event.message,
                        )
                    }
                }
            }
        }
    }

    /**
     * Inject a one-line summary of the angler's history into the new session.
     *
     * The backend computes this brief when it mints the token (it is cheap and
     * server-side); this is where it actually reaches the model. Sent through
     * `clientContent` with `turnComplete = false`, which is the documented way
     * to add context without requesting a response.
     *
     * Deliberately a *summary*, not a transcript: the catch log would cost far
     * more context than it is worth, and the model can call
     * `get_recent_catches` for detail whenever it needs it.
     */
    private suspend fun primeFishingContext() {
        val brief = credentials.lastUserBrief
        val turkish = language.startsWith("tr")

        // The clock comes from the device, not the backend: the phone is at the
        // spot and already knows the local time, so asking a server to work it
        // out from coordinates would be a slower way to be less certain.
        val now = ZonedDateTime.now()
        val clock = now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT))
        val day = now.dayOfWeek.getDisplayName(
            TextStyle.FULL,
            if (turkish) Locale.forLanguageTag("tr") else Locale.ENGLISH,
        )

        val context = buildString {
            append(if (turkish) "[Oturum bağlamı] " else "[Session context] ")
            append(
                if (turkish) {
                    "Şu an $day, saat $clock (balıkçının telefonundaki yerel saat). "
                } else {
                    "It is $day, $clock local time on the angler's phone. "
                },
            )

            val place = brief?.get("place") as? JsonObject
            val placeName = (place?.get("name") as? JsonPrimitive)?.contentOrNull
            val region = (place?.get("region") as? JsonPrimitive)?.contentOrNull
            if (placeName != null) {
                val where = if (region != null && region != placeName) {
                    "$placeName ($region)"
                } else {
                    placeName
                }
                append(
                    if (turkish) {
                        "Balıkçı şu anda $where civarında. "
                    } else {
                        "The angler is at $where right now. "
                    },
                )
            }

            // Whether there is water where they are standing. The backend
            // sends a flag rather than a distance on purpose; see
            // `live.py`. Stated in both directions, because "no sea state"
            // has to read as a fact the assistant was told, not as something
            // missing from the brief that it can quietly ignore.
            (brief?.get("sea_state_at_position") as? JsonPrimitive)?.booleanOrNull?.let { atWater ->
                append(
                    if (turkish) {
                        if (atWater) {
                            "Bulunduğu noktada deniz durumu var; kıyıda olması muhtemel. "
                        } else {
                            "Bulunduğu noktada deniz durumu yok; iç bölgede, sudan uzakta. "
                        }
                    } else {
                        if (atWater) {
                            "There is a sea state at their position, so they are " +
                                "plausibly on the coast. "
                        } else {
                            "There is no sea state at their position: they are " +
                                "inland, away from the water. "
                        }
                    },
                )
            }

            (brief?.get("display_name") as? JsonPrimitive)?.contentOrNull?.let {
                append(if (turkish) "Adı $it. " else "Their name is $it. ")
            }
            (brief?.get("total_sessions") as? JsonPrimitive)?.contentOrNull?.let {
                append(if (turkish) "Kayıtlı $it oturum" else "$it recorded sessions")
            }
            (brief?.get("total_catches") as? JsonPrimitive)?.contentOrNull?.let {
                append(if (turkish) ", $it av var. " else ", $it catches. ")
            }
            (brief?.get("go_to_lures") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?.let {
                    append(
                        if (turkish) {
                            "Sık kullandığı sahteler: ${it.joinToString(", ")}. "
                        } else {
                            "Their go-to lures: ${it.joinToString(", ")}. "
                        },
                    )
                }
            append(
                if (turkish) {
                    "Detay için araçları kullan; bu özeti kullanıcıya okuma."
                } else {
                    "Use the tools for detail; never read this summary out."
                },
            )
        }

        // The directive is a separate turn from the facts so the model treats
        // it as an instruction rather than as something to recite.
        //
        // What it must NOT do is propose the next move. This used to end with
        // "ask to see the water", and that is wrong in half of all sessions:
        // the same angler opens this app at the kitchen table to plan
        // tomorrow, work out what to take and find out what they are missing.
        // An opening that proposes an action is definitely wrong in one of the
        // two cases, and the angler's own first sentence tells us which one it
        // is for free. So the opening proves it knows the place and the hour,
        // and then gets out of the way.
        //
        // Inland is the one exception, and it is not a proposal: with no water
        // anywhere near them there is nothing to be said about conditions
        // where they stand, so asking where they are headed is what makes the
        // rest of the conversation possible at all.
        val atWater = (brief?.get("sea_state_at_position") as? JsonPrimitive)
            ?.booleanOrNull ?: true
        val directive = if (turkish) {
            "[Başla] Şimdi sen konuşmaya başla. Nerede olduğunu sorma — yukarıda " +
                "yazıyor. Yeri ve saati kullanarak selam ver, get_current_conditions " +
                "çağırıp kısa bir tavır al, sonra " +
                (
                    if (atWater) {
                        "sus ve onun konuşmasını bekle. Ne yapacağını önermiyorsun, " +
                            "takım adı söylemiyorsun, suyu göstermesini istemiyorsun, " +
                            "kamerayı açmıyorsun."
                    } else {
                        "sudan uzakta olduğunu söyleyip nerede avlanmayı " +
                            "düşündüğünü sor; suyu göstermesini isteme."
                    }
                    ) +
                " En fazla iki üç cümle."
        } else {
            "[Begin] Speak first, now. Do not ask where they are — it is above. " +
                "Greet them using the spot and the time, call get_current_conditions " +
                "and give a short opinion, then " +
                (
                    if (atWater) {
                        "stop and wait for them. Do not propose what to do, do not " +
                            "name any tackle, do not ask to see the water and do not " +
                            "open the camera."
                    } else {
                        "say they are away from the water and ask where they are " +
                            "planning to fish; do not ask to see anything."
                    }
                    ) +
                " Two or three sentences at most."
        }

        runCatching {
            session.primeContext(
                listOf(PrimedTurn.user(context), PrimedTurn.user(directive)),
                requestReply = true,
            )
        }.onFailure { Log2.w(TAG, "context priming skipped", it) }
    }

    private suspend fun executeTools(calls: List<ToolCallRequest>) {
        _state.update {
            it.copy(
                phase = AssistantPhase.TOOL_CALL,
                toolActivity = it.toolActivity + calls.map { call -> call.name },
            )
        }

        // Some tools act on hardware the backend cannot reach, so they are
        // executed here and never posted. See DEVICE_TOOLS.
        val (deviceCalls, backendCalls) = calls.partition { it.name in DEVICE_TOOLS }

        val deviceResults = deviceCalls.map { executeDeviceTool(it) }
        val backendResults = if (backendCalls.isEmpty()) {
            emptyList()
        } else {
            val position = locationProvider.lastKnown()
            toolOrchestrator.execute(
                backendCalls,
                ToolExecutionContext(
                    language = language,
                    liveSessionRef = liveSessionRef,
                    latitude = position?.latitude,
                    longitude = position?.longitude,
                ),
            )
        }

        // Answer in the order the model asked, so a turn mixing a device tool
        // with a backend one still lines up call for call.
        val byKey = (deviceResults + backendResults).associateBy { (it.id ?: "") + it.name }
        val ordered = calls.mapNotNull { byKey[(it.id ?: "") + it.name] }
        session.sendToolResponses(ordered)
        armReplyWatchdog()
        _state.update { it.copy(phase = AssistantPhase.THINKING) }
    }

    private suspend fun executeDeviceTool(call: ToolCallRequest): ToolResult = when (call.name) {
        TOOL_SET_CAMERA -> {
            val enabled = call.args?.get("enabled")?.jsonPrimitive?.booleanOrNull
            if (enabled == null) {
                Log2.w(TAG, "set_camera called without a usable 'enabled'")
                deviceToolError(
                    call,
                    "set_camera needs 'enabled' to be true or false.",
                    "Ask the user whether you should look, in one short sentence.",
                )
            } else {
                applyCameraRequest(call, enabled)
            }
        }

        else -> {
            // Unreachable unless DEVICE_TOOLS and this dispatch drift apart.
            Log2.e(TAG, "no device handler for ${call.name}")
            deviceToolError(
                call,
                "'${call.name}' is not something this device can do.",
                "Say you cannot do that right now, in one short sentence, and do not retry.",
            )
        }
    }

    /**
     * Turn the camera on or off because the model asked.
     *
     * This is what makes "do you see this?" work without the angler touching
     * anything. Two details matter and both were learned the hard way:
     *
     * The result is returned only **after** a frame has actually gone out, so
     * the model is looking at something by the time it speaks. Returning
     * immediately makes it answer about a camera that has not produced a frame
     * yet, and it either stalls or invents.
     *
     * And a failure must come back as a failure. A model told "camera on" that
     * then sees nothing will describe a fish it cannot see, which is the one
     * thing this product must never do.
     */
    private suspend fun applyCameraRequest(call: ToolCallRequest, enabled: Boolean): ToolResult {
        if (!enabled) {
            setCameraEnabled(false)
            return ToolResult(
                id = call.id,
                name = call.name,
                response = buildJsonObject {
                    put("camera", "off")
                    put(
                        "instruction_to_model",
                        "The camera is off and you can no longer see. Do not describe " +
                            "anything visual from now on. Say nothing about this unless " +
                            "the user asks.",
                    )
                },
            )
        }

        val alreadyWatching = _state.value.cameraEnabled && _state.value.framesSent > 0
        val arrived = if (alreadyWatching) true else awaitLookFrames()
        if (!arrived) {
            setCameraEnabled(false)
            return deviceToolError(
                call,
                _state.value.errorMessage ?: "The camera did not produce an image.",
                "Tell the user in one short sentence that you cannot see right now, " +
                    "then keep helping with what they told you. Do not describe " +
                    "anything visual and do not retry.",
            )
        }
        return ToolResult(
            id = call.id,
            name = call.name,
            response = buildJsonObject {
                put("camera", "on")
                put(
                    "instruction_to_model",
                    "You can see now, and several frames have already been sent. " +
                        "Describe what is actually in them, separating what you observe " +
                        "from what you infer. " +
                        "If the view is dark, blurred, or clearly not what you asked to " +
                        "see — a pocket, the ground, the angler themselves — then say so, " +
                        "ask them to point the phone at it, and KEEP THE CAMERA ON. Do " +
                        "not close it and do not describe something you cannot make out: " +
                        "they are holding a rod and need a moment to aim. " +
                        "Call set_camera with enabled=false only once you have actually " +
                        "seen the thing and finished with it.",
                )
            },
        )
    }

    /**
     * Start the stream and suspend until one frame has gone to the model.
     *
     * The timeout is not optional: a camera that never binds (permission just
     * revoked, hardware held by another app) would otherwise leave the tool
     * call unanswered, and an unanswered tool call hangs the conversation for
     * good.
     */
    /**
     * Watch for the answer that should follow a tool response.
     *
     * `docs/GEMINI_LIVE_IMPLEMENTATION.md` records the service failing on the
     * turn *after* a tool response. It is usually a 1011, which reconnects on
     * its own — but it has also been seen on the device as plain silence: the
     * responses were accepted, the socket stayed open, no error arrived and
     * nothing was ever generated. The session sits in "Thinking" until the
     * angler gives up, which they did, fifteen seconds later.
     *
     * Silence cannot be distinguished from a model that is simply taking its
     * time, so this waits well past any normal reply before acting, and then
     * does the same thing the 1011 path does: reconnect on the resumption
     * handle, which continues the conversation rather than restarting it.
     */
    private fun armReplyWatchdog() {
        replyWatchdog?.cancel()
        replyWatchdog = scope.launch {
            delay(REPLY_WATCHDOG_MS)
            if (_state.value.waitingForInput) return@launch
            Log2.w(TAG, "no reply ${REPLY_WATCHDOG_MS}ms after a tool response; reconnecting")
            when (val issued = credentials.current(language, expectsVideo = _state.value.cameraEnabled)) {
                is AppResult.Ok ->
                    session.connect(issued.value, resumeHandle = _state.value.resumeHandle)
                is AppResult.Err ->
                    Log2.w(TAG, "watchdog reconnect could not mint: ${issued.error.message}")
            }
        }
    }

    /** Anything from the model means the turn is alive; stand the watchdog down. */
    private fun cancelReplyWatchdog() {
        replyWatchdog?.cancel()
        replyWatchdog = null
    }

    /**
     * The angler started talking over the assistant.
     *
     * Called from the capture thread the moment the gate latches, which is a
     * round trip earlier than the server's own `interrupted`. Stopping here
     * is what makes cutting in feel immediate: otherwise the assistant keeps
     * talking for as long as it takes the frames to reach Google, be judged
     * by VAD, and the verdict to come back.
     *
     * Deliberately only playback and state. The frames themselves keep
     * flowing — the server still has to hear the interruption to act on it —
     * and nothing here touches the socket, so a false positive costs a
     * truncated sentence rather than a broken session. The server's
     * `interrupted` event remains the authority and is still handled; this
     * just gets there first, and both paths are idempotent.
     */
    /**
     * Stop the assistant talking, now, because the angler said so.
     *
     * Deterministic, and that is the whole point. Separating a voice from the
     * loudspeaker's own echo by level has almost no headroom on a phone held
     * at arm's length — measured, repeatedly — so voice barge-in is kept at
     * the conservative setting that does not make the assistant interrupt
     * itself, and this is here for when that setting is too conservative. It
     * needs no threshold, no calibration and no room to be quiet.
     */
    fun interrupt() = onLocalBargeIn()

    private fun onLocalBargeIn() {
        scope.launch {
            drainJob?.cancel()
            drainJob = null
            audioPlayer.flush()
            _state.update {
                if (it.phase == AssistantPhase.SPEAKING) {
                    it.copy(phase = AssistantPhase.LISTENING)
                } else {
                    it
                }
            }
            Log2.i(TAG, "local barge-in: playback stopped before the server replied")
        }
    }

    /**
     * Leave SPEAKING when the speaker goes quiet, not when the server stops
     * generating.
     *
     * Those are far apart. The service generates faster than real time and
     * hands over the whole turn long before it has been heard: measured at
     * 16.5 s of speech delivered in 5.0 s, with `generationComplete` arriving
     * at 5.04 s — **12.65 s of audio still unplayed**. Flipping to LISTENING
     * there froze the avatar's mouth a couple of syllables in and labelled
     * the screen "Listening" while the assistant was still talking.
     *
     * Polled rather than driven by `audioPlayer.playing`, because that flag
     * goes false when our queue empties and ignores the tail already inside
     * the device buffer; [AudioPlayer.isAudible] accounts for both.
     */
    private fun awaitPlaybackDrained() {
        drainJob?.cancel()
        drainJob = scope.launch {
            while (audioPlayer.isAudible) delay(DRAIN_POLL_MS)
            _state.update {
                // Only if nothing started speaking again in the meantime.
                if (it.phase != AssistantPhase.SPEAKING) {
                    it
                } else {
                    it.copy(phase = if (it.isMuted) AssistantPhase.IDLE else AssistantPhase.LISTENING)
                }
            }
            drainJob = null
        }
    }

    /**
     * Hold the microphone until the assistant has started its greeting.
     *
     * Bounded, because a model that says nothing must not leave the angler
     * holding a dead microphone: on timeout the mic opens anyway and the
     * session behaves exactly as it did before.
     */
    private suspend fun awaitGreetingStart() {
        var waiter = greetingWaiter ?: return
        if (withTimeoutOrNull(GREETING_START_TIMEOUT_MS) { waiter.await() } != null) {
            greetingWaiter = null
            return
        }

        // Nothing came back. Observed on the device with a healthy socket:
        // setup completed, the priming was sent, and the model produced no
        // audio, no tool call and no error — the same two turns answered
        // normally against the service a minute later. Asking once more turns
        // a silent flake into a second of delay instead of an angler left
        // staring at "Listening" wondering whether to start talking.
        Log2.w(TAG, "no greeting within ${GREETING_START_TIMEOUT_MS}ms; asking once more")
        waiter = CompletableDeferred()
        greetingWaiter = waiter
        primeFishingContext()
        if (withTimeoutOrNull(GREETING_RETRY_TIMEOUT_MS) { waiter.await() } != null) {
            greetingWaiter = null
            return
        }

        Log2.w(TAG, "still no greeting; opening the mic and letting the angler lead")
        _state.update { it.copy(phase = AssistantPhase.LISTENING) }
        greetingWaiter = null
    }

    /**
     * Open the camera and let it actually see something before answering.
     *
     * One frame used to be enough to return, and it is not. At the default
     * half a frame per second that single frame is whatever the phone
     * happened to be pointing at in the instant the model decided to look —
     * a pocket, the ground, the angler's chest — because nobody has raised
     * the phone yet. The model then described that, or hedged, and closed
     * the camera again. On the shore it reads as the camera blinking on and
     * off without perceiving anything.
     *
     * So a look waits for several frames, and raises the frame rate while it
     * waits so the wait stays short: [CAMERA_LOOK_FRAMES] at
     * [CAMERA_LOOK_FPS] is about a second and a half of footage rather than
     * one snapshot. The angler's own setting is restored afterwards, since
     * the elevated rate is for looking, not for the whole session.
     */
    private suspend fun awaitLookFrames(): Boolean {
        val before = _state.value.frameConfig.framesPerSecond
        val startedAt = _state.value.framesSent
        setFrameRate(CAMERA_LOOK_FPS)
        val waiter = CompletableDeferred<Boolean>()
        firstFrameWaiter = waiter
        setCameraEnabled(true)
        return try {
            val first = withTimeoutOrNull(CAMERA_FIRST_FRAME_TIMEOUT_MS) { waiter.await() }
            if (first != true) {
                Log2.w(TAG, "camera produced no frame within ${CAMERA_FIRST_FRAME_TIMEOUT_MS}ms")
                return false
            }
            // Give it a few more before saying "you can see now".
            withTimeoutOrNull(CAMERA_LOOK_TIMEOUT_MS) {
                while (_state.value.framesSent - startedAt < CAMERA_LOOK_FRAMES) delay(80)
            }
            val got = _state.value.framesSent - startedAt
            Log2.i(TAG, "look ready after $got frame(s)")
            true
        } finally {
            firstFrameWaiter = null
            setFrameRate(before)
        }
    }

    private fun deviceToolError(
        call: ToolCallRequest,
        message: String,
        instruction: String,
    ): ToolResult = ToolResult(
        id = call.id,
        name = call.name,
        response = buildJsonObject {
            put("error", "device_tool_failed")
            put("message", message)
            put("instruction_to_model", instruction)
        },
    )

    /**
     * Mint a fresh token and resume.
     *
     * A Live token lasts 30 minutes but a shore session can run for hours, so
     * this path is expected, not exceptional.
     */
    /**
     * Mint a fresh token and open a new socket. **Single-flight.**
     *
     * An expired token is reported twice: once as a `LiveEvent.Error` and
     * once as `LiveConnectionState.Failed`, and both are observed here. Both
     * used to call this, so the app minted two tokens, opened two sockets,
     * completed setup on both and primed both — two live conversations at
     * once, each answering the angler and neither aware of the other. In the
     * session that surfaced this the symptom was not audio at all: it sat in
     * "Listening", then in "Thinking", and never came back.
     *
     * Deduplicating at the source is not possible — the two signals are
     * genuinely different facts and either can arrive first — so the guard
     * lives here, where the decision to open a socket is made.
     */
    private suspend fun renewCredentialsAndReconnect() {
        if (renewGuard?.isActive == true) {
            Log2.d(TAG, "credentials renewed moments ago; ignoring duplicate trigger")
            return
        }
        // A window rather than a lock. A concurrency guard is not enough —
        // the two reports can arrive either side of a completed renewal, and
        // the mint is fast enough that they do. Nor can "are we connected?"
        // decide it: a token can genuinely die on an open socket, which
        // `expired token triggers a re-mint and resumes with the handle`
        // pins. What is always true is that one dead token deserves one new
        // socket, and two reports of it arrive together.
        renewGuard = scope.launch { delay(RENEW_DEDUPE_MS) }
        renewCredentialsNow()
    }

    private suspend fun renewCredentialsNow() {
        Log2.i(TAG, "renewing live credentials")
        _state.update { it.copy(phase = AssistantPhase.RECONNECTING) }
        credentials.invalidate()
        val position = locationProvider.lastKnown()
        when (
            val issued = credentials.current(
                language,
                expectsVideo = _state.value.cameraEnabled,
                latitude = position?.latitude,
                longitude = position?.longitude,
            )
        ) {
            is AppResult.Ok ->
                session.connect(issued.value, resumeHandle = _state.value.resumeHandle)
            is AppResult.Err -> {
                // Nothing is in flight now, so a later trigger must be able
                // to try again at once.
                renewGuard?.cancel()
                _state.update {
                    it.copy(phase = AssistantPhase.ERROR, errorMessage = issued.error.message)
                }
            }
        }
    }

    companion object {
        private const val TAG = "FishingMode"

        /**
         * How long the microphone stays closed after the last sample is
         * written. The AudioTrack buffer plus room reverberation outlive the
         * queue going empty, and a shorter tail let the final syllable echo
         * back as a fresh interruption.
         */
        private const val ECHO_TAIL_MS = 400L

        /** The one tool name the device answers for; see [DEVICE_TOOLS]. */
        internal const val TOOL_SET_CAMERA = "set_camera"

        /**
         * Tools executed here instead of being posted to the backend.
         *
         * They act on hardware the server cannot reach. The names must match
         * `DEVICE_TOOLS` in `backend/app/tools/declarations.py`, which is where
         * the declaration the model sees is defined; a name in one set and not
         * the other is the bug this pairing exists to make obvious.
         */
        internal val DEVICE_TOOLS = setOf(TOOL_SET_CAMERA)

        /**
         * How long a `set_camera` call waits for the first frame.
         *
         * CameraX binding plus the first YUV→JPEG conversion is a few hundred
         * milliseconds on a mid-range phone; this leaves generous room while
         * still being far short of the point where the angler decides the app
         * has frozen.
         */
        private const val CAMERA_FIRST_FRAME_TIMEOUT_MS = 2_500L

        /**
         * Frames a look waits for before the model is told it can see.
         *
         * Four, not one, and the difference is the angler's arm. When the
         * model asks to see a rod or a lure box, nobody is pointing the phone
         * at it yet: the first frame is a pocket or the ground, and a look
         * built on it reads as a camera that blinked and perceived nothing.
         * Four frames at [CAMERA_LOOK_FPS] is about two seconds — long enough
         * to raise a phone, short enough that the model is not silent for an
         * uncomfortable gap.
         */
        private const val CAMERA_LOOK_FRAMES = 4

        /**
         * Frame rate while a look is in progress.
         *
         * Four times the resting rate, for a second or two. The resting rate
         * is chosen for a three-hour session on a phone battery; this is
         * chosen for the few seconds when the model is actually looking at
         * something, and the angler's setting is put back afterwards.
         */
        private const val CAMERA_LOOK_FPS = 2.0

        /**
         * Upper bound on that wait, so a stalled camera cannot hang a turn.
         * Has to clear [CAMERA_LOOK_FRAMES] at [CAMERA_LOOK_FPS] plus CameraX
         * binding, or the timeout decides every look and the frame count is
         * decoration.
         */
        private const val CAMERA_LOOK_TIMEOUT_MS = 4_500L

        /**
         * How long the microphone waits for the greeting to begin.
         *
         * Covers a token mint, the socket, priming and the model's first
         * token. Past this the greeting is not coming, and a shut microphone
         * would be the worse failure.
         */
        private const val GREETING_START_TIMEOUT_MS = 6_000L

        /**
         * The second ask is given less time than the first: the socket has
         * already proved itself, so a reply that is coming arrives quickly,
         * and a longer wait is just more silence.
         */
        private const val GREETING_RETRY_TIMEOUT_MS = 4_000L

        /** Fine enough that the mouth stops with the voice, cheap enough to idle. */
        private const val DRAIN_POLL_MS = 50L

        /**
         * How long one dead token's reports are collapsed into a single
         * renewal. Comfortably longer than a mint and a socket open, and far
         * shorter than any real token lifetime.
         */
        private const val RENEW_DEDUPE_MS = 3_000L

        /**
         * How long to wait for the answer after a tool response before
         * treating the turn as lost. Long enough that a slow but working
         * model is never cut off; short enough that the angler has not
         * already given up, which on the device took fifteen seconds.
         */
        private const val REPLY_WATCHDOG_MS = 10_000L
    }
}

/** A camera frame held for display, with whether the model received it. */
class CameraPreviewFrame(
    val jpeg: ByteArray,
    val sent: Boolean,
    val capturedAtEpochMs: Long = System.currentTimeMillis(),
)

/** Everything Fishing Mode needs to render, and nothing else. */
data class FishingModeState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    val connection: LiveConnectionState = LiveConnectionState.Idle,
    val isMuted: Boolean = false,
    val isListening: Boolean = false,
    val cameraEnabled: Boolean = false,
    /**
     * Where the assistant's voice is actually coming out, as reported by
     * `AudioManager`. The UI shows this rather than a stored preference, so a
     * disconnected earbud cannot leave the screen claiming Bluetooth.
     */
    val audioRoute: AudioRoute = AudioRoute.UNKNOWN,
    /**
     * Whether hands-free output is engaged for this session.
     *
     * Separate from [audioRoute]: the route says which device, this says
     * whether we are driving it hands-free at all. Turning it off hands
     * routing back to the system (the earpiece, for a voice-communication
     * stream) and is a deliberate choice, never a side effect of echo
     * control, a reconnect or a recomposition.
     */
    val handsFree: Boolean = true,
    val isWatching: Boolean = false,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val frameConfig: FrameConfig = FrameConfig(),
    val transcript: List<TranscriptLine> = emptyList(),
    /**
     * Partial transcription of what the user is saying right now. Held apart
     * from [transcript] because it is replaced, not accumulated.
     */
    val interimTranscript: String = "",
    val toolActivity: List<String> = emptyList(),
    /** Citations from the most recent Google-Search-grounded answer. */
    val groundingSources: List<GroundingSource> = emptyList(),
    val groundingQueries: List<String> = emptyList(),
    /** The model is intentionally silent, waiting for more input. */
    val waitingForInput: Boolean = false,
    val errorMessage: String? = null,
    val resumeHandle: String? = null,
    // Cost/usage telemetry surfaced in Settings -> Usage.
    val framesSent: Int = 0,
    /** Frames dropped by the adaptive policy; a cost-saving signal. */
    val framesSkipped: Int = 0,
    val bytesSentApprox: Long = 0,
    val totalTokens: Int = 0,
    val interruptions: Int = 0,
    /**
     * Sentences the assistant said more than once — the symptom of the echo
     * loop, surfaced so it can be read off a real session instead of guessed at.
     */
    val repeatedSentences: Int = 0,
) {
    val isReconnecting: Boolean get() = phase == AssistantPhase.RECONNECTING

    /** The one-line status shown under the orb. */
    val statusKey: String
        get() = when {
            errorMessage != null && phase == AssistantPhase.ERROR -> "error"
            phase == AssistantPhase.RECONNECTING -> "reconnecting"
            phase == AssistantPhase.CONNECTING -> "connecting"
            phase == AssistantPhase.TOOL_CALL -> "tool"
            phase == AssistantPhase.THINKING -> "thinking"
            phase == AssistantPhase.SPEAKING -> "speaking"
            isMuted -> "muted"
            isWatching -> "watching"
            phase == AssistantPhase.LISTENING -> "listening"
            else -> "idle"
        }
}

/** Visual states required by the brief, kept one-to-one with the UI. */
enum class AssistantPhase {
    IDLE,
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    WATCHING,
    TOOL_CALL,
    RECONNECTING,
    ERROR,
}

enum class TranscriptSpeaker { USER, ASSISTANT }

data class TranscriptLine(
    val speaker: TranscriptSpeaker,
    val text: String,
    val atEpochMs: Long = System.currentTimeMillis(),
    /**
     * Subtitle in the reader's language, filled in asynchronously.
     *
     * Null means "not translated yet" and renders as nothing rather than as a
     * placeholder: a missing subtitle must not look like a broken turn.
     */
    val translation: String? = null,
    /** Stable across merges, so an arriving translation finds its line. */
    val id: Long = nextTranscriptLineId(),
)

private val transcriptLineIds = java.util.concurrent.atomic.AtomicLong(0L)

private fun nextTranscriptLineId(): Long = transcriptLineIds.incrementAndGet()

/**
 * Append transcript text, merging into the previous line from the same speaker.
 *
 * Live transcription arrives as fragments ("Bu", " küçük", " bir soft plastic"),
 * so without merging the transcript becomes an unreadable column of
 * single-word rows.
 */
internal fun List<TranscriptLine>.append(
    speaker: TranscriptSpeaker,
    text: String,
): List<TranscriptLine> {
    val last = lastOrNull()
    val merged = if (last != null && last.speaker == speaker) {
        // Keep the id so a pending translation still matches, but clear the
        // translation itself: it described a shorter sentence.
        dropLast(1) + last.copy(text = (last.text + text).trimStart(), translation = null)
    } else {
        this + TranscriptLine(speaker, text.trimStart())
    }
    // Bound the transcript: a two-hour session would otherwise grow without
    // limit in memory and in the lazy list.
    return if (merged.size > MAX_TRANSCRIPT_LINES) {
        merged.takeLast(MAX_TRANSCRIPT_LINES)
    } else {
        merged
    }
}

private const val MAX_TRANSCRIPT_LINES = 200

/**
 * Count sentences the assistant said more than once.
 *
 * This is *the* symptom to watch for in a hands-free session. When the model
 * hears its own voice on the loudspeaker, server-side VAD reads it as a
 * barge-in, the model stops and starts the same sentence again — so the
 * transcript fills with the same phrase over and over. Because restarts merge
 * into the line already being spoken, the repetition usually appears *within*
 * one line rather than across lines, which is why this looks inside the text
 * instead of comparing lines.
 *
 * Short fragments are ignored: "yes", "tamam" and "mm" legitimately recur in
 * conversation and would otherwise swamp the count.
 *
 * Mirrors `countRepeatedSentences` in `web/src/live/transcript.ts`.
 */
internal fun countRepeatedSentences(text: String): Int {
    val sentences = text
        .split(SENTENCE_BREAK)
        .map(::normalizeForRepeats)
        .filter { it.split(' ').size >= MIN_REPEAT_WORDS }

    val seen = mutableMapOf<String, Int>()
    for (sentence in sentences) seen[sentence] = (seen[sentence] ?: 0) + 1
    return seen.values.sumOf { if (it > 1) it - 1 else 0 }
}


private fun normalizeForRepeats(text: String): String = text
    // `lowercase()` without a locale is deliberate: it is locale-invariant,
    // whereas a Turkish locale would fold I to ı and make the same sentence
    // compare unequal depending on the phone's language.
    .lowercase()
    .replace(PUNCTUATION, " ")
    .replace(WHITESPACE, " ")
    .trim()

private val SENTENCE_BREAK = Regex("(?<=[.!?…])\\s+|\\n+")
private val PUNCTUATION = Regex("[.,!?;:…\"'`´‘’“”()-]")
private val WHITESPACE = Regex("\\s+")

/** Below this, a recurring phrase is conversation rather than a stutter. */
private const val MIN_REPEAT_WORDS = 4
