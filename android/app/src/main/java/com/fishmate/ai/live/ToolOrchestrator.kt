package com.fishmate.ai.live

import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.repository.ToolRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Executes the model's function calls by delegating to the backend.
 *
 * Why the device does not execute tools itself: the data lives server-side
 * (tackle, sessions, catch history), and the device must not be trusted to
 * decide what a tool returns. So a `toolCall` becomes an authenticated HTTPS
 * request, and the backend validates the arguments against its Pydantic models
 * before touching anything. This is the loop described in
 * docs/GEMINI_LIVE.md -> "Function calling".
 *
 * The contract that matters: **every call gets a response**. If a tool fails,
 * or the network is down, we still answer the model with an error object.
 * Leaving a `toolCall` unanswered hangs the conversation — the assistant goes
 * silent and the angler thinks the app broke.
 */
class ToolOrchestrator(
    private val toolRepository: ToolRepository,
) {

    suspend fun execute(
        calls: List<ToolCallRequest>,
        context: ToolExecutionContext,
    ): List<ToolResult> {
        if (calls.isEmpty()) return emptyList()
        Log2.i(TAG, "executing ${calls.size} tool call(s): ${calls.joinToString { it.name }}")

        val results = runCatching {
            toolRepository.executeBatch(calls, context)
        }.getOrElse { error ->
            Log2.w(TAG, "tool batch failed entirely", error)
            // Synthesise a response per call so the model is never left hanging.
            calls.map { call ->
                ToolResult(
                    id = call.id,
                    name = call.name,
                    response = offlineResponse(),
                )
            }
        }

        // Defensive: if the backend somehow returned fewer results than calls,
        // fill the gaps rather than silently dropping a response.
        if (results.size != calls.size) {
            Log2.w(TAG, "tool result count ${results.size} != call count ${calls.size}")
            val byKey = results.associateBy { (it.id ?: "") + it.name }
            return calls.map { call ->
                byKey[(call.id ?: "") + call.name] ?: ToolResult(
                    id = call.id,
                    name = call.name,
                    response = offlineResponse(),
                )
            }
        }
        return results
    }

    private fun offlineResponse(): JsonObject = buildJsonObject {
        put("error", "tool_unreachable")
        put(
            "message",
            "I couldn't reach that information right now.",
        )
        put(
            "instruction_to_model",
            "Tell the user you could not retrieve that, in one short sentence, then " +
                "keep helping with what you already know. Do not invent the answer.",
        )
    }

    companion object {
        private const val TAG = "ToolOrchestrator"
    }
}

/** Per-turn context the backend needs to scope and locate a tool call. */
data class ToolExecutionContext(
    val language: String,
    val liveSessionRef: String?,
    val latitude: Double?,
    val longitude: Double?,
)
