package com.fishmate.ai.live

import com.fishmate.ai.core.Log2
import com.fishmate.ai.live.protocol.Blob
import com.fishmate.ai.live.protocol.ClientContent
import com.fishmate.ai.live.protocol.ClientContentFrame
import com.fishmate.ai.live.protocol.Content
import com.fishmate.ai.live.protocol.FunctionResponse
import com.fishmate.ai.live.protocol.Part
import com.fishmate.ai.live.protocol.LiveJson
import com.fishmate.ai.live.protocol.RealtimeInput
import com.fishmate.ai.live.protocol.RealtimeInputFrame
import com.fishmate.ai.live.protocol.ServerMessage
import com.fishmate.ai.live.protocol.SetupFrame
import com.fishmate.ai.live.protocol.ToolResponse
import com.fishmate.ai.live.protocol.ToolResponseFrame
import com.fishmate.ai.live.protocol.parseDurationMillis
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Live API session over a raw OkHttp WebSocket.
 *
 * Why a raw socket rather than an SDK: the Google Gen AI SDK for Kotlin is
 * explicitly **not supported on Android** (Google directs mobile clients to
 * Firebase AI Logic or to connecting directly). Connecting directly with a
 * constrained ephemeral token is the documented client-to-server pattern, and
 * it gives us the control this product needs over reconnection, compression
 * and media throttling. See docs/ARCHITECTURE.md -> "Why no Kotlin GenAI SDK".
 *
 * Responsibilities kept deliberately narrow: frame the protocol, surface
 * events, and manage the socket's lifecycle. Audio capture, playback, camera
 * throttling and tool execution all live elsewhere.
 */
class GeminiLiveSession(
    private val client: OkHttpClient = defaultClient(),
    private val reconnectPolicy: ReconnectPolicy = ReconnectPolicy(),
    /** Injectable so tests can reconnect without real delays. */
    private val sleeper: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
) : LiveAiSession {

    // Reconnect work is launched from OkHttp's callback thread, so the scope
    // carries its own dispatcher rather than inheriting that thread.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _connectionState = MutableStateFlow<LiveConnectionState>(LiveConnectionState.Idle)
    override val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(
        replay = 0,
        // Audio arrives faster than a slow collector can drain. Dropping the
        // oldest chunk is right: stale audio is worse than a brief gap, and
        // suspending here would stall the socket reader.
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<LiveEvent> = _events.asSharedFlow()

    private val sendLock = Mutex()
    private var socket: WebSocket? = null
    private var credentials: LiveCredentials? = null

    /**
     * Latest resumption handle from the server.
     *
     * This is the single most important piece of state in the class: with it,
     * a dropped connection resumes the same conversation; without it, the user
     * loses context mid-sentence.
     */
    @Volatile
    private var resumptionHandle: String? = null

    @Volatile
    private var closedByUs = false

    @Volatile
    private var attempt = 0

    val currentResumptionHandle: String? get() = resumptionHandle

    // -----------------------------------------------------------------------
    // Connect
    // -----------------------------------------------------------------------
    override suspend fun connect(credentials: LiveCredentials, resumeHandle: String?) {
        this.credentials = credentials
        if (resumeHandle != null) resumptionHandle = resumeHandle
        closedByUs = false
        openSocket()
    }

    private fun openSocket() {
        val creds = credentials ?: run {
            emit(LiveEvent.Error("no_credentials", "No Live credentials available.", fatal = true))
            return
        }
        _connectionState.value =
            if (attempt == 0) LiveConnectionState.Connecting
            else LiveConnectionState.Reconnecting(attempt, "retrying")

        val request = Request.Builder().url(creds.wsUrl).build()
        Log2.i(TAG, "opening live socket model=${creds.model} resuming=${resumptionHandle != null}")
        socket = client.newWebSocket(request, Listener())
    }

    /**
     * Build the first frame.
     *
     * The config comes from the backend untouched; only `sessionResumption` is
     * rewritten, because the handle is client-side state the server cannot
     * know about.
     */
    internal fun buildSetupFrame(creds: LiveCredentials, handle: String?): String {
        // `creds.setup` is the complete `BidiGenerateContentSetup` body built
        // by our backend from the SDK's own converter — `model`,
        // `generationConfig`, `systemInstruction`, `tools`, … as siblings.
        //
        // It deliberately has no `config` wrapper: that is an SDK abstraction,
        // and sending it is rejected with
        // `Unknown name "config" at 'setup': Cannot find field.`
        //
        // The only thing the client adds is the resumption handle, which is
        // client-side state the server cannot know.
        val merged = buildJsonObject {
            creds.setup.forEach { (key, value) ->
                if (key != "sessionResumption") put(key, value)
            }
            put(
                "sessionResumption",
                buildJsonObject { if (handle != null) put("handle", handle) },
            )
        }
        return LiveJson.instance.encodeToString(SetupFrame.serializer(), SetupFrame(merged))
    }

    // -----------------------------------------------------------------------
    // Send
    // -----------------------------------------------------------------------
    override suspend fun sendAudio(pcm: ByteArray, length: Int) {
        if (length <= 0) return
        val slice = if (length == pcm.size) pcm else pcm.copyOf(length)
        val mime = credentials?.inputAudioMime ?: "audio/pcm;rate=16000"
        sendFrame(
            LiveJson.instance.encodeToString(
                RealtimeInputFrame.serializer(),
                RealtimeInputFrame(
                    RealtimeInput(audio = Blob(data = base64(slice), mimeType = mime)),
                ),
            ),
            // Audio is the highest-volume frame type; logging each one would
            // flood logcat and leak conversation timing.
            quiet = true,
        )
    }

    override suspend fun sendImage(jpeg: ByteArray) {
        sendFrame(
            LiveJson.instance.encodeToString(
                RealtimeInputFrame.serializer(),
                RealtimeInputFrame(
                    RealtimeInput(video = Blob(data = base64(jpeg), mimeType = "image/jpeg")),
                ),
            ),
            quiet = true,
        )
        Log2.d(TAG, "sent camera frame bytes=${jpeg.size}")
    }

    override suspend fun sendText(text: String) {
        if (text.isBlank()) return
        sendFrame(
            LiveJson.instance.encodeToString(
                RealtimeInputFrame.serializer(),
                RealtimeInputFrame(RealtimeInput(text = text)),
            ),
        )
    }

    override suspend fun primeContext(turns: List<PrimedTurn>, requestReply: Boolean) {
        if (turns.isEmpty()) return
        val frame = ClientContentFrame(
            ClientContent(
                turns = turns.map { turn ->
                    Content(role = turn.role, parts = listOf(Part(text = turn.text)))
                },
                // Normally false: true interrupts whatever is generating, and
                // adding context is not asking a question. True only for the
                // opening turn, where a reply is exactly what we want.
                turnComplete = requestReply,
            ),
        )
        sendFrame(LiveJson.instance.encodeToString(ClientContentFrame.serializer(), frame))
        Log2.i(TAG, "primed context with ${turns.size} turn(s) reply=$requestReply")
    }

    override suspend fun sendToolResponses(responses: List<ToolResult>) {
        if (responses.isEmpty()) return
        val frame = ToolResponseFrame(
            ToolResponse(
                responses.map {
                    FunctionResponse(
                        id = it.id,
                        name = it.name,
                        response = it.response,
                        scheduling = it.scheduling?.wireValue,
                    )
                },
            ),
        )
        sendFrame(LiveJson.instance.encodeToString(ToolResponseFrame.serializer(), frame))
        Log2.i(TAG, "sent ${responses.size} tool response(s): ${responses.joinToString { it.name }}")
    }

    override suspend fun signalAudioStreamEnd() {
        sendFrame(
            LiveJson.instance.encodeToString(
                RealtimeInputFrame.serializer(),
                RealtimeInputFrame(RealtimeInput(audioStreamEnd = true)),
            ),
        )
    }

    private suspend fun sendFrame(json: String, quiet: Boolean = false) {
        val ws = socket
        if (ws == null || _connectionState.value !is LiveConnectionState.Connected) {
            // Dropping is correct: realtime media has no value once late, and
            // queueing it would replay stale audio after a reconnect.
            if (!quiet) Log2.d(TAG, "frame dropped, socket not connected")
            return
        }
        sendLock.withLock {
            val accepted = runCatching { ws.send(json) }.getOrDefault(false)
            if (!accepted && !quiet) Log2.w(TAG, "socket rejected a frame (buffer full?)")
        }
    }

    // -----------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------
    override suspend fun disconnect() {
        closedByUs = true
        attempt = 0
        sendLock.withLock {
            runCatching { socket?.close(NORMAL_CLOSURE, "client disconnect") }
            socket = null
        }
        _connectionState.value = LiveConnectionState.Closed
        Log2.i(TAG, "live session closed by client")
    }

    override fun release() {
        closedByUs = true
        runCatching { socket?.cancel() }
        socket = null
        scope.cancel()
    }

    private fun emit(event: LiveEvent) {
        _events.tryEmit(event)
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    // -----------------------------------------------------------------------
    // Socket callbacks
    // -----------------------------------------------------------------------
    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            val creds = credentials ?: return
            val frame = buildSetupFrame(creds, resumptionHandle)
            if (!webSocket.send(frame)) {
                Log2.e(TAG, "failed to send setup frame")
                return
            }
            Log2.i(TAG, "socket open, setup sent")
        }

        override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

        private fun handle(payload: String) {
            val message = runCatching {
                LiveJson.instance.decodeFromString(ServerMessage.serializer(), payload)
            }.getOrElse { error ->
                Log2.w(TAG, "unparseable server message", error)
                return
            }

            message.setupComplete?.let {
                attempt = 0
                _connectionState.value = LiveConnectionState.Connected
                emit(LiveEvent.Ready)
                Log2.i(TAG, "setup complete, session live")
            }

            message.serverContent?.let { content ->
                // Interruption first: dropping queued audio promptly is what
                // makes barge-in feel immediate.
                if (content.interrupted == true) emit(LiveEvent.Interrupted)

                // Interim first: it arrives while the user is still talking,
                // and is superseded by the finalised transcription below.
                content.interimInputTranscription?.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(LiveEvent.InterimInputTranscript(it)) }
                content.inputTranscription?.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(LiveEvent.InputTranscript(it)) }
                content.outputTranscription?.text
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { emit(LiveEvent.OutputTranscript(it)) }

                // Grounding citations: the evidence that the built-in Google
                // Search tool ran. Surfaced, not discarded.
                content.groundingMetadata?.let { grounding ->
                    val sources = grounding.groundingChunks
                        .mapNotNull { chunk ->
                            chunk.web?.uri?.let { uri ->
                                GroundingSource(title = chunk.web.title, uri = uri)
                            }
                        }
                        .distinctBy { it.uri }
                    if (sources.isNotEmpty() || grounding.webSearchQueries.isNotEmpty()) {
                        Log2.i(
                            TAG,
                            "grounded answer: ${sources.size} source(s), " +
                                "${grounding.webSearchQueries.size} query(ies)",
                        )
                        emit(LiveEvent.Grounding(grounding.webSearchQueries, sources))
                    }
                }

                content.waitingForInput?.let { emit(LiveEvent.WaitingForInput(it)) }

                content.modelTurn?.parts?.forEach { part ->
                    part.inlineData?.let { blob ->
                        runCatching { Base64.getDecoder().decode(blob.data) }
                            .onSuccess { emit(LiveEvent.AudioChunk(it)) }
                            .onFailure { Log2.w(TAG, "bad audio payload") }
                    }
                    // A text part arrives when the model emits text alongside
                    // audio; surface it as transcript rather than dropping it.
                    part.text
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { emit(LiveEvent.OutputTranscript(it)) }
                }

                if (content.generationComplete == true) emit(LiveEvent.GenerationComplete)
                if (content.turnComplete == true) emit(LiveEvent.TurnComplete)
            }

            message.toolCall?.functionCalls
                ?.takeIf { it.isNotEmpty() }
                ?.let { calls ->
                    Log2.i(TAG, "tool call: ${calls.joinToString { it.name }}")
                    emit(
                        LiveEvent.ToolCalls(
                            calls.map { ToolCallRequest(it.id, it.name, it.args) },
                        ),
                    )
                }

            message.toolCallCancellation?.ids
                ?.takeIf { it.isNotEmpty() }
                ?.let { emit(LiveEvent.ToolCallsCancelled(it)) }

            message.sessionResumptionUpdate?.let { update ->
                val handle = update.newHandle
                if (!handle.isNullOrBlank() && update.resumable != false) {
                    resumptionHandle = handle
                    emit(LiveEvent.ResumptionHandle(handle))
                }
            }

            message.goAway?.let { goAway ->
                val left = parseDurationMillis(goAway.timeLeft)
                Log2.i(TAG, "server goAway, timeLeft=${left}ms - will resume")
                emit(LiveEvent.GoingAway(left))
                // Reconnect ahead of the cut so the conversation does not gap.
                scope.launch { reconnectSoon(delayMs = computeProactiveDelay(left)) }
            }

            message.usageMetadata?.let { emit(LiveEvent.Usage(it.totalTokenCount)) }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            Log2.w(TAG, "socket failure code=$code ${t.message}", t)
            socket = null
            if (closedByUs) return

            // 401/403 mean the token is finished: a new one must be minted, so
            // retrying this socket would just fail the same way.
            if (code == 401 || code == 403) {
                _connectionState.value =
                    LiveConnectionState.Failed("token_expired", "Live credentials expired.")
                emit(LiveEvent.Error("token_expired", "Live credentials expired.", fatal = true))
                return
            }
            scope.launch { reconnectSoon() }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log2.i(TAG, "socket closing code=$code reason=$reason")
            runCatching { webSocket.close(NORMAL_CLOSURE, null) }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (closedByUs) {
                _connectionState.value = LiveConnectionState.Closed
                return
            }
            Log2.i(TAG, "socket closed unexpectedly code=$code - resuming")
            scope.launch { reconnectSoon() }
        }
    }

    /**
     * How long to wait before a proactive reconnect after `goAway`.
     *
     * Leave a safety margin so the new socket is up before the old one dies,
     * but never wait longer than the server said we have.
     */
    internal fun computeProactiveDelay(timeLeftMs: Long?): Long {
        if (timeLeftMs == null) return 0L
        return (timeLeftMs - reconnectPolicy.goAwaySafetyMarginMs).coerceAtLeast(0L)
    }

    private suspend fun reconnectSoon(delayMs: Long? = null) {
        if (closedByUs) return
        val creds = credentials ?: return

        // Two different deadlines live on an ephemeral token, and conflating
        // them is fatal:
        //   * expireTime (~30 min) — how long an ALREADY OPEN connection may
        //     keep sending.
        //   * newSessionExpireTime (~60 s) — how long the token may be used to
        //     OPEN a socket at all.
        //
        // Every reconnect opens a *new* socket, so it needs the second one.
        // Checking only expiry meant that any drop more than a minute into a
        // session retried with a token the server would no longer accept,
        // failing with `1011 new_session_expire_time deadline exceeded` until
        // the retries ran out and the user was told Gemini was unreachable.
        if (creds.isExpired() || !creds.canStartNewSession()) {
            // The controller owns credential renewal; tell it rather than
            // burning retries on a token that cannot open a socket.
            Log2.i(
                TAG,
                "token cannot open a new session (expired=${creds.isExpired()}); re-minting",
            )
            _connectionState.value =
                LiveConnectionState.Failed("token_expired", "Live credentials expired.")
            emit(LiveEvent.Error("token_expired", "Live credentials expired.", fatal = true))
            return
        }

        attempt += 1
        if (attempt > reconnectPolicy.maxAttempts) {
            _connectionState.value = LiveConnectionState.Failed(
                "reconnect_exhausted",
                "I can't reach Gemini right now. Check your connection and try again.",
            )
            emit(
                LiveEvent.Error(
                    "reconnect_exhausted",
                    "I can't reach Gemini right now. Check your connection and try again.",
                    fatal = true,
                ),
            )
            return
        }

        val wait = delayMs ?: reconnectPolicy.backoffMillis(attempt)
        _connectionState.value = LiveConnectionState.Reconnecting(
            attempt,
            if (delayMs != null) "server rotation" else "connection lost",
        )
        Log2.i(TAG, "reconnect attempt=$attempt in ${wait}ms")
        sleeper(wait)
        if (!closedByUs) openSocket()
    }

    companion object {
        private const val TAG = "GeminiLive"
        private const val NORMAL_CLOSURE = 1000

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // No read timeout: a Live socket is legitimately silent while the
            // angler says nothing. Liveness is covered by pings instead.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Backoff for transient drops, which on a shoreline are frequent. */
data class ReconnectPolicy(
    val maxAttempts: Int = 6,
    val baseDelayMs: Long = 500,
    val maxDelayMs: Long = 8_000,
    /** Reconnect this far ahead of a `goAway` deadline. */
    val goAwaySafetyMarginMs: Long = 1_500,
) {
    fun backoffMillis(attempt: Int): Long {
        val exponential = baseDelayMs shl (attempt - 1).coerceIn(0, 16)
        // Jitter avoids a thundering herd when many clients drop together
        // (one cell tower losing signal on a breakwater).
        val jitter = (0..250).random()
        return (exponential + jitter).coerceAtMost(maxDelayMs)
    }
}
