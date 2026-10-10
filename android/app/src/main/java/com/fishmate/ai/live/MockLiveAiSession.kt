package com.fishmate.ai.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Scriptable [LiveAiSession] for tests.
 *
 * Automated tests must never depend on real Gemini usage: it is slow,
 * non-deterministic and costs money per run. This records what was sent and
 * lets a test drive any server event, including the awkward ones that are hard
 * to provoke for real — `goAway`, interruption, a tool call with bad arguments.
 *
 * Note this is a *test* double. Demo Mode deliberately uses the real session
 * (docs/DEMO.md): the point of the demo is that Gemini Live is genuinely
 * running.
 */
class MockLiveAiSession : LiveAiSession {

    private val _connectionState = MutableStateFlow<LiveConnectionState>(LiveConnectionState.Idle)
    override val connectionState: StateFlow<LiveConnectionState> = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<LiveEvent>(replay = 0, extraBufferCapacity = 128)
    override val events: Flow<LiveEvent> = _events.asSharedFlow()

    // ---- recorded interactions, for assertions ----------------------------
    val sentAudioChunks = mutableListOf<ByteArray>()
    val sentImages = mutableListOf<ByteArray>()
    val sentText = mutableListOf<String>()
    val sentToolResults = mutableListOf<List<ToolResult>>()

    /** How many times the client asked the model to speak first. */
    var openingTurnsRequested = 0
        private set
    val primedContext = mutableListOf<List<PrimedTurn>>()
    var audioStreamEndCount = 0
        private set
    var connectCount = 0
        private set
    var lastResumeHandle: String? = null
        private set
    var released = false
        private set

    /** Set to have [connect] fail, exercising the error path. */
    var failOnConnect: LiveEvent.Error? = null

    override suspend fun connect(credentials: LiveCredentials, resumeHandle: String?) {
        connectCount += 1
        lastResumeHandle = resumeHandle
        _connectionState.value = LiveConnectionState.Connecting
        failOnConnect?.let { failure ->
            _connectionState.value = LiveConnectionState.Failed(failure.code, failure.message)
            _events.emit(failure)
            return
        }
        _connectionState.value = LiveConnectionState.Connected
        _events.emit(LiveEvent.Ready)
    }

    override suspend fun sendAudio(pcm: ByteArray, length: Int) {
        sentAudioChunks += if (length == pcm.size) pcm.copyOf() else pcm.copyOf(length)
    }

    override suspend fun sendImage(jpeg: ByteArray) {
        sentImages += jpeg.copyOf()
    }

    override suspend fun sendText(text: String) {
        sentText += text
    }

    override suspend fun primeContext(turns: List<PrimedTurn>, requestReply: Boolean) {
        primedContext += turns
        if (requestReply) openingTurnsRequested += 1
    }

    override suspend fun sendToolResponses(responses: List<ToolResult>) {
        sentToolResults += responses
    }

    override suspend fun signalAudioStreamEnd() {
        audioStreamEndCount += 1
    }

    override suspend fun disconnect() {
        _connectionState.value = LiveConnectionState.Closed
    }

    override fun release() {
        released = true
    }

    // ---- test drivers -----------------------------------------------------
    suspend fun emitEvent(event: LiveEvent) = _events.emit(event)

    /**
     * Drive the connection state directly, for reconnect behaviour that no
     * sequence of events can reproduce.
     */
    fun emitConnection(state: LiveConnectionState) {
        _connectionState.value = state
    }

    suspend fun emitAudio(bytes: ByteArray) = _events.emit(LiveEvent.AudioChunk(bytes))

    suspend fun emitToolCall(name: String, args: JsonObject, id: String? = "call-1") =
        _events.emit(LiveEvent.ToolCalls(listOf(ToolCallRequest(id, name, args))))

    suspend fun emitTranscript(user: String? = null, model: String? = null) {
        user?.let { _events.emit(LiveEvent.InputTranscript(it)) }
        model?.let { _events.emit(LiveEvent.OutputTranscript(it)) }
    }

    suspend fun emitInterruption() = _events.emit(LiveEvent.Interrupted)

    suspend fun emitInterimTranscript(text: String) =
        _events.emit(LiveEvent.InterimInputTranscript(text))

    suspend fun emitGrounding(queries: List<String>, sources: List<GroundingSource>) =
        _events.emit(LiveEvent.Grounding(queries, sources))

    suspend fun emitGoAway(timeLeftMs: Long? = 5_000) =
        _events.emit(LiveEvent.GoingAway(timeLeftMs))

    suspend fun emitResumptionHandle(handle: String) =
        _events.emit(LiveEvent.ResumptionHandle(handle))

    fun setState(state: LiveConnectionState) {
        _connectionState.value = state
    }
}
