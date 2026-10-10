package com.fishmate.ai.live.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Gemini Live API BidiGenerateContent wire messages.
 *
 * Field names are verified against the current Live API WebSocket reference
 * (see ../../../../../../../docs/GEMINI_LIVE.md, which records the exact
 * message shapes and where they came from). Getting one of these names wrong
 * fails silently — the server simply ignores an unknown field — so they are
 * declared explicitly rather than relying on Kotlin property names.
 *
 * Only the fields FishMate actually uses are modelled. `ignoreUnknownKeys`
 * means new server fields are tolerated rather than crashing a live session
 * mid-conversation.
 */
object LiveJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
        isLenient = true
    }
}

// ---------------------------------------------------------------------------
// Client -> server
// ---------------------------------------------------------------------------

/**
 * First frame on every connection.
 *
 * The body is the wire `BidiGenerateContentSetup`: `model`,
 * `generationConfig`, `systemInstruction`, `tools`, `realtimeInputConfig`,
 * `sessionResumption`, `contextWindowCompression` and the transcription
 * settings, all as siblings. There is **no** `config` wrapper — that belongs
 * to the SDK's object model, and sending it is rejected with
 * `Unknown name "config" at 'setup'`.
 *
 * It is produced by our backend (which runs the official SDK's own converter)
 * and sent verbatim: the ephemeral token is locked to this configuration, so
 * the client does not get to choose the model, system instruction or tool set.
 * Carried as a [JsonObject] because the only thing the client adds is a
 * resumption handle.
 */
@Serializable
data class SetupFrame(val setup: JsonObject)

@Serializable
data class RealtimeInputFrame(
    @SerialName("realtimeInput") val realtimeInput: RealtimeInput,
)

@Serializable
data class RealtimeInput(
    /** 16-bit PCM, 16 kHz, mono, little-endian, base64. */
    @SerialName("audio") val audio: Blob? = null,
    /** A single JPEG frame, base64. Video is sent as throttled still frames. */
    @SerialName("video") val video: Blob? = null,
    @SerialName("text") val text: String? = null,
    /** Tells the server the user's audio stream has ended. */
    @SerialName("audioStreamEnd") val audioStreamEnd: Boolean? = null,
    @SerialName("activityStart") val activityStart: JsonObject? = null,
    @SerialName("activityEnd") val activityEnd: JsonObject? = null,
)

@Serializable
data class Blob(
    @SerialName("data") val data: String,
    @SerialName("mimeType") val mimeType: String,
)

@Serializable
data class ToolResponseFrame(
    @SerialName("toolResponse") val toolResponse: ToolResponse,
)

@Serializable
data class ToolResponse(
    @SerialName("functionResponses") val functionResponses: List<FunctionResponse>,
)

@Serializable
data class FunctionResponse(
    /** Echo of the call id so the server matches it to its request. */
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String,
    @SerialName("response") val response: JsonObject,
    /**
     * When a tool was declared `NON_BLOCKING`, this tells the server what to
     * do with the result: `INTERRUPT`, `WHEN_IDLE` or `SILENT`. Omitted for
     * ordinary (blocking) tools, where it has no meaning.
     */
    @SerialName("scheduling") val scheduling: String? = null,
)

@Serializable
data class ClientContentFrame(
    @SerialName("clientContent") val clientContent: ClientContent,
)

@Serializable
data class ClientContent(
    @SerialName("turns") val turns: List<Content>,
    /**
     * Required, with no default — the same trap documented on [Content.role],
     * which caught this field too.
     *
     * `LiveJson` sets `encodeDefaults = false`. While this defaulted to
     * `true`, asking for a reply (`turnComplete = true`) produced a value
     * *equal to the default*, so the field was stripped from the JSON and the
     * server read the frame as `turnComplete: false` — context accepted, no
     * generation requested. The assistant stayed silent through every opening
     * while the client logged a successful send. Priming with `false` was
     * unaffected, which is why only the greeting was broken.
     */
    @SerialName("turnComplete") val turnComplete: Boolean,
)

@Serializable
data class Content(
    /**
     * Nullable with a null default, not `= "user"`.
     *
     * Two constraints meet here. `LiveJson` sets `encodeDefaults = false`, so
     * a role defaulted to `"user"` would be *stripped* when we encode — and
     * `clientContent` is documented as using explicit `user` / `model` roles,
     * so mixed-role priming would break. But the server's own `modelTurn`
     * often arrives with no role at all, so it cannot be required either.
     * Null default satisfies both: absent on decode, always emitted when we
     * set it explicitly.
     */
    @SerialName("role") val role: String? = null,
    @SerialName("parts") val parts: List<Part>,
)

@Serializable
data class Part(
    @SerialName("text") val text: String? = null,
    @SerialName("inlineData") val inlineData: Blob? = null,
)

// ---------------------------------------------------------------------------
// Server -> client
// ---------------------------------------------------------------------------

@Serializable
data class ServerMessage(
    @SerialName("setupComplete") val setupComplete: JsonObject? = null,
    @SerialName("serverContent") val serverContent: ServerContent? = null,
    @SerialName("toolCall") val toolCall: ToolCall? = null,
    @SerialName("toolCallCancellation") val toolCallCancellation: ToolCallCancellation? = null,
    @SerialName("goAway") val goAway: GoAway? = null,
    @SerialName("sessionResumptionUpdate")
    val sessionResumptionUpdate: SessionResumptionUpdate? = null,
    @SerialName("usageMetadata") val usageMetadata: UsageMetadata? = null,
)

@Serializable
data class ServerContent(
    @SerialName("modelTurn") val modelTurn: Content? = null,
    /** The user spoke over the model: stop playback immediately (barge-in). */
    @SerialName("interrupted") val interrupted: Boolean? = null,
    @SerialName("turnComplete") val turnComplete: Boolean? = null,
    @SerialName("generationComplete") val generationComplete: Boolean? = null,
    /** Finalised transcription of what the user said. */
    @SerialName("inputTranscription") val inputTranscription: Transcription? = null,
    /**
     * Low-latency partial transcription, updated *while* the user is still
     * speaking. Rendering this is what makes the transcript feel live rather
     * than arriving a sentence at a time.
     */
    @SerialName("interimInputTranscription")
    val interimInputTranscription: Transcription? = null,
    @SerialName("outputTranscription") val outputTranscription: Transcription? = null,
    /**
     * Citations for a Google-Search-grounded answer. This is the *evidence*
     * that the built-in Search tool ran, so it is surfaced rather than
     * discarded — it matters most for regulation questions, where the user
     * needs to see the source.
     */
    @SerialName("groundingMetadata") val groundingMetadata: GroundingMetadata? = null,
    @SerialName("urlContextMetadata") val urlContextMetadata: JsonElement? = null,
    /** The model is deliberately not generating: it is waiting for more input. */
    @SerialName("waitingForInput") val waitingForInput: Boolean? = null,
    @SerialName("interactionStatus") val interactionStatus: JsonElement? = null,
)

@Serializable
data class GroundingMetadata(
    @SerialName("groundingChunks") val groundingChunks: List<GroundingChunk> = emptyList(),
    @SerialName("webSearchQueries") val webSearchQueries: List<String> = emptyList(),
)

@Serializable
data class GroundingChunk(
    @SerialName("web") val web: GroundingWeb? = null,
)

@Serializable
data class GroundingWeb(
    @SerialName("uri") val uri: String? = null,
    @SerialName("title") val title: String? = null,
)

@Serializable
data class Transcription(@SerialName("text") val text: String? = null)

@Serializable
data class ToolCall(
    @SerialName("functionCalls") val functionCalls: List<FunctionCall> = emptyList(),
)

@Serializable
data class FunctionCall(
    @SerialName("id") val id: String? = null,
    @SerialName("name") val name: String,
    @SerialName("args") val args: JsonObject? = null,
)

@Serializable
data class ToolCallCancellation(
    @SerialName("ids") val ids: List<String> = emptyList(),
)

/**
 * The server is about to close the connection.
 *
 * This is routine, not an error: a Live connection lasts roughly ten minutes.
 * Receiving it is the cue to reconnect using the stored resumption handle
 * *before* being cut off, so the user hears no gap.
 */
@Serializable
data class GoAway(
    /** Protobuf duration string, e.g. "9.5s". */
    @SerialName("timeLeft") val timeLeft: String? = null,
)

@Serializable
data class SessionResumptionUpdate(
    @SerialName("newHandle") val newHandle: String? = null,
    @SerialName("resumable") val resumable: Boolean? = null,
)

@Serializable
data class UsageMetadata(
    @SerialName("totalTokenCount") val totalTokenCount: Int? = null,
    @SerialName("promptTokenCount") val promptTokenCount: Int? = null,
    @SerialName("responseTokenCount") val responseTokenCount: Int? = null,
)

/** Parse a protobuf duration string ("9.5s", "1m30s") to milliseconds. */
fun parseDurationMillis(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val match = Regex("""(?:(\d+)m)?([\d.]+)s""").matchEntire(value.trim()) ?: return null
    val minutes = match.groupValues[1].toLongOrNull() ?: 0L
    val seconds = match.groupValues[2].toDoubleOrNull() ?: return null
    return minutes * 60_000L + (seconds * 1000).toLong()
}
