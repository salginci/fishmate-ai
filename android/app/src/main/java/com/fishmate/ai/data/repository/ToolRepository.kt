package com.fishmate.ai.data.repository

import com.fishmate.ai.core.AppResult
import com.fishmate.ai.core.Log2
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.ToolCallBatchRequest
import com.fishmate.ai.data.remote.dto.ToolCallDto
import com.fishmate.ai.live.ToolCallRequest
import com.fishmate.ai.live.ToolExecutionContext
import com.fishmate.ai.live.ToolResult
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Forwards Gemini function calls to the backend for execution.
 *
 * One batch request per `toolCall` message, because Gemini expects a single
 * `toolResponse` containing every `functionResponse` for that turn.
 */
class ToolRepository(
    private val api: FishMateApi,
    private val syncManager: SyncManager,
) {

    suspend fun executeBatch(
        calls: List<ToolCallRequest>,
        context: ToolExecutionContext,
    ): List<ToolResult> {
        val request = ToolCallBatchRequest(
            calls.map { call ->
                ToolCallDto(
                    name = call.name,
                    callId = call.id,
                    args = call.args ?: buildJsonObject {},
                    language = context.language,
                    liveSessionRef = context.liveSessionRef,
                    latitude = context.latitude,
                    longitude = context.longitude,
                )
            },
        )

        return when (val result = apiCall("tools") { api.executeTools(request) }) {
            is AppResult.Ok -> {
                // A tool that changed server state (a logged catch, a finished
                // session) has invalidated our local cache.
                if (calls.any { it.name in MUTATING_TOOLS }) {
                    syncManager.requestRefresh()
                }
                result.value.results.map {
                    ToolResult(id = it.callId, name = it.name, response = it.response)
                }
            }
            is AppResult.Err -> {
                Log2.w("ToolRepository", "batch failed: ${result.error.code}")
                calls.map { call ->
                    ToolResult(
                        id = call.id,
                        name = call.name,
                        response = buildJsonObject {
                            put("error", result.error.code)
                            put("message", result.error.message)
                            put(
                                "instruction_to_model",
                                "Tell the user you could not retrieve that in one short " +
                                    "sentence, then continue helping with what you know. " +
                                    "Do not invent the answer.",
                            )
                        },
                    )
                }
            }
        }
    }

    companion object {
        /** Tools whose success means our local mirror is stale. */
        private val MUTATING_TOOLS = setOf(
            "start_fishing_session",
            "finish_fishing_session",
            "log_catch",
            "add_session_observation",
        )
    }
}
