package com.fishmate.ai

import com.fishmate.ai.data.repository.ToolRepository
import com.fishmate.ai.live.ToolCallRequest
import com.fishmate.ai.live.ToolExecutionContext
import com.fishmate.ai.live.ToolOrchestrator
import com.fishmate.ai.live.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The orchestrator's one hard contract: **every tool call gets a response**.
 *
 * An unanswered `toolCall` hangs the Live conversation — the assistant simply
 * goes quiet and the angler assumes the app broke. So network failures,
 * exceptions and short/mismatched result sets must all still produce one
 * response per call.
 */
class ToolOrchestratorTest {

    private val context = ToolExecutionContext(
        language = "tr",
        liveSessionRef = "ref",
        latitude = 38.46,
        longitude = 27.09,
    )

    private fun call(name: String, id: String?) = ToolCallRequest(id, name, buildJsonObject { })

    @Test
    fun `passes through successful results`() = runTest {
        val repository: ToolRepository = mockk()
        coEvery { repository.executeBatch(any(), any()) } returns listOf(
            ToolResult("c1", "get_current_conditions", buildJsonObject { put("summary", "calm") }),
        )
        val results = ToolOrchestrator(repository)
            .execute(listOf(call("get_current_conditions", "c1")), context)

        assertEquals(1, results.size)
        assertEquals("calm", results.single().response["summary"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a thrown exception still produces one response per call`() = runTest {
        val repository: ToolRepository = mockk()
        coEvery { repository.executeBatch(any(), any()) } throws IOException("offline")

        val calls = listOf(call("get_tackle_inventory", "a"), call("get_recent_catches", "b"))
        val results = ToolOrchestrator(repository).execute(calls, context)

        assertEquals(2, results.size)
        assertEquals(listOf("a", "b"), results.map { it.id })
        results.forEach { result ->
            assertTrue(result.response.containsKey("error"))
            // And the model is told what to do with the failure.
            assertTrue(result.response.containsKey("instruction_to_model"))
            assertTrue(
                result.response["instruction_to_model"]!!.jsonPrimitive.content
                    .contains("Do not invent"),
            )
        }
    }

    @Test
    fun `a short result set is padded so no call goes unanswered`() = runTest {
        val repository: ToolRepository = mockk()
        // The backend answered only one of the two calls.
        coEvery { repository.executeBatch(any(), any()) } returns listOf(
            ToolResult("a", "get_tackle_inventory", buildJsonObject { put("total", 3) }),
        )
        val calls = listOf(call("get_tackle_inventory", "a"), call("get_recent_catches", "b"))
        val results = ToolOrchestrator(repository).execute(calls, context)

        assertEquals(2, results.size)
        assertEquals(setOf("a", "b"), results.map { it.id }.toSet())
        val padded = results.single { it.id == "b" }
        assertTrue(padded.response.containsKey("error"))
    }

    @Test
    fun `empty call list is a no-op`() = runTest {
        val repository: ToolRepository = mockk()
        assertTrue(ToolOrchestrator(repository).execute(emptyList(), context).isEmpty())
    }

    @Test
    fun `calls without ids are still matched by name`() = runTest {
        val repository: ToolRepository = mockk()
        coEvery { repository.executeBatch(any(), any()) } returns listOf(
            ToolResult(null, "get_sun_times", buildJsonObject { put("sunrise", "07:12") }),
        )
        val results = ToolOrchestrator(repository)
            .execute(listOf(call("get_sun_times", null)), context)
        assertEquals("get_sun_times", results.single().name)
    }
}
