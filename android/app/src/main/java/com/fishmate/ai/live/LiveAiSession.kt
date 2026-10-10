package com.fishmate.ai.live

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Abstraction over a Gemini Live session.
 *
 * Nothing above this interface knows about WebSockets, base64 or the Live
 * protocol, which is what makes [MockLiveAiSession] possible and keeps the
 * session state machine unit-testable (docs/ARCHITECTURE.md).
 */
interface LiveAiSession {

    /** Connection lifecycle, suitable for driving UI state directly. */
    val connectionState: StateFlow<LiveConnectionState>

    /** Everything the session produces. Cold-start safe; replay is not implied. */
    val events: Flow<LiveEvent>

    /**
     * Open a session.
     *
     * @param credentials short-lived, config-locked credentials from our backend
     * @param resumeHandle a handle from a previous [LiveEvent.ResumptionHandle],
     *   which continues the conversation instead of starting fresh
     */
    suspend fun connect(credentials: LiveCredentials, resumeHandle: String? = null)

    /**
     * Stream microphone audio.
     *
     * @param pcm 16-bit PCM, 16 kHz, mono, little-endian
     * @param length valid bytes in [pcm]; the buffer is reused by the caller
     */
    suspend fun sendAudio(pcm: ByteArray, length: Int = pcm.size)

    /** Send one JPEG frame as realtime video input. */
    suspend fun sendImage(jpeg: ByteArray)

    /** Send typed text (used by the transcript input and Demo Mode). */
    suspend fun sendText(text: String)

    /**
     * Seed the conversation with prior context.
     *
     * Uses the Live API's `clientContent` channel, which is the documented way
     * to "add messages to the model context in order". This is how the
     * angler's history, their spot and the local time reach a brand-new
     * session: a few short lines rather than a replayed transcript.
     *
     * @param requestReply maps to `turnComplete`. Left false, the context is
     *   added silently, which is what any mid-session update wants —
     *   `turnComplete = true` interrupts whatever is generating. Set true
     *   exactly once, at session open, to make the assistant greet first: a
     *   voice-first product that waits to be spoken to feels inert, and it is
     *   also what stops it opening with "where are you fishing?" when the
     *   answer was in the context all along.
     */
    suspend fun primeContext(turns: List<PrimedTurn>, requestReply: Boolean = false)

    /** Answer the model's function calls. */
    suspend fun sendToolResponses(responses: List<ToolResult>)

    /** Tell the server the user's audio has stopped (mute, or end of turn). */
    suspend fun signalAudioStreamEnd()

    /** Close the socket but keep any resumption handle for a later reconnect. */
    suspend fun disconnect()

    /** Release everything. The instance is unusable afterwards. */
    fun release()
}

/** Credentials as issued by `POST /api/v1/live/session`. */
data class LiveCredentials(
    val token: String,
    val wsUrl: String,
    val model: String,
    /** Server-built setup body, sent verbatim as the first frame. */
    val setup: JsonObject,
    val inputSampleRateHz: Int,
    val outputSampleRateHz: Int,
    val inputAudioMime: String,
    val expiresAtEpochMs: Long,
    val newSessionExpiresAtEpochMs: Long,
) {
    fun isExpired(nowMs: Long = System.currentTimeMillis()) = nowMs >= expiresAtEpochMs

    /** True when a *new* session can still be opened with this token. */
    fun canStartNewSession(nowMs: Long = System.currentTimeMillis()) =
        nowMs < newSessionExpiresAtEpochMs
}

sealed interface LiveConnectionState {
    data object Idle : LiveConnectionState
    data object Connecting : LiveConnectionState
    data object Connected : LiveConnectionState

    /**
     * Temporarily down and retrying. The UI shows "Reconnecting…" rather than
     * an error, because this is the expected path roughly every ten minutes.
     */
    data class Reconnecting(val attempt: Int, val reason: String) : LiveConnectionState

    data class Failed(val code: String, val message: String) : LiveConnectionState
    data object Closed : LiveConnectionState
}

sealed interface LiveEvent {
    /** The server accepted our setup; audio may now flow. */
    data object Ready : LiveEvent

    /** A chunk of the model's speech: 16-bit PCM at the output sample rate. */
    class AudioChunk(val pcm: ByteArray) : LiveEvent

    data class InputTranscript(val text: String) : LiveEvent

    /**
     * Partial transcription while the user is still speaking.
     *
     * Distinct from [InputTranscript], which is the finalised text. Rendering
     * the interim one is what makes the transcript feel live.
     */
    data class InterimInputTranscript(val text: String) : LiveEvent

    data class OutputTranscript(val text: String) : LiveEvent

    /**
     * Citations behind a Google-Search-grounded answer.
     *
     * Surfaced rather than discarded: for a regulation question this is the
     * source the user needs to see, and it is the observable proof that the
     * built-in Search tool ran inside the Live session.
     */
    data class Grounding(
        val queries: List<String>,
        val sources: List<GroundingSource>,
    ) : LiveEvent

    /** The model is intentionally silent, waiting for more input. */
    data class WaitingForInput(val waiting: Boolean) : LiveEvent

    /** The user talked over the model. Playback must be dropped immediately. */
    data object Interrupted : LiveEvent

    data object TurnComplete : LiveEvent
    data object GenerationComplete : LiveEvent

    data class ToolCalls(val calls: List<ToolCallRequest>) : LiveEvent
    data class ToolCallsCancelled(val ids: List<String>) : LiveEvent

    /** Store this; it is what makes a seamless reconnect possible. */
    data class ResumptionHandle(val handle: String) : LiveEvent

    /** The server will close shortly. Reconnect proactively. */
    data class GoingAway(val timeLeftMs: Long?) : LiveEvent

    data class Usage(val totalTokens: Int?) : LiveEvent
    data class Error(val code: String, val message: String, val fatal: Boolean) : LiveEvent
}

data class ToolCallRequest(
    val id: String?,
    val name: String,
    val args: JsonObject?,
)

data class ToolResult(
    val id: String?,
    val name: String,
    val response: JsonObject,
    /**
     * Only meaningful for a tool declared `NON_BLOCKING`: tells the server
     * whether to interrupt, wait until idle, or stay silent with the result.
     */
    val scheduling: ToolScheduling? = null,
)

/** `FunctionResponse.scheduling` values for asynchronous function calling. */
enum class ToolScheduling(val wireValue: String) {
    INTERRUPT("INTERRUPT"),
    WHEN_IDLE("WHEN_IDLE"),
    SILENT("SILENT"),
}

data class GroundingSource(val title: String?, val uri: String)

/** One turn of primed conversation context. */
data class PrimedTurn(val role: String, val text: String) {
    companion object {
        fun user(text: String) = PrimedTurn("user", text)
        fun model(text: String) = PrimedTurn("model", text)
    }
}
