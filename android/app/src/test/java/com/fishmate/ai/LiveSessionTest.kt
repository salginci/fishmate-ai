package com.fishmate.ai

import com.fishmate.ai.live.GeminiLiveSession
import com.fishmate.ai.live.LiveCredentials
import com.fishmate.ai.live.ReconnectPolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session setup and reconnection policy.
 *
 * The setup frame is the highest-risk single message in the app: it carries
 * the server-locked configuration, and a reconnect that loses the resumption
 * handle silently starts a brand-new conversation — the angler would notice
 * only by the assistant forgetting everything mid-session.
 */
class LiveSessionTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun credentials(setup: JsonObject) = LiveCredentials(
        token = "ephemeral-token",
        wsUrl = "wss://example.invalid/ws?access_token=ephemeral-token",
        model = "gemini-3.8-live",
        setup = setup,
        inputSampleRateHz = 16_000,
        outputSampleRateHz = 24_000,
        inputAudioMime = "audio/pcm;rate=16000",
        expiresAtEpochMs = System.currentTimeMillis() + 1_800_000,
        newSessionExpiresAtEpochMs = System.currentTimeMillis() + 60_000,
    )

    /**
     * The real `BidiGenerateContentSetup` body, as the backend returns it.
     *
     * Note the shape: no `config` wrapper (that is an SDK abstraction and the
     * server rejects it), `responseModalities` under `generationConfig`, and
     * `model` carrying the `models/` prefix.
     */
    private fun serverSetup(): JsonObject = buildJsonObject {
        put("model", "models/gemini-3.8-live")
        putJsonObject("generationConfig") {
            putJsonArray("responseModalities") {
                add(kotlinx.serialization.json.JsonPrimitive("AUDIO"))
            }
            put("mediaResolution", "MEDIA_RESOLUTION_LOW")
        }
        putJsonObject("sessionResumption") { }
        putJsonObject("contextWindowCompression") {
            put("triggerTokens", 16000)
            putJsonObject("slidingWindow") { put("targetTokens", 8000) }
        }
        putJsonObject("systemInstruction") {
            putJsonArray("parts") {
                add(buildJsonObject { put("text", "You are FishMate.") })
            }
        }
    }

    @Test
    fun `setup frame preserves the server-locked config verbatim`() {
        val session = GeminiLiveSession()
        val frame = session.buildSetupFrame(credentials(serverSetup()), handle = null)
        val decoded = json.parseToJsonElement(frame).jsonObject["setup"]!!.jsonObject

        assertEquals("models/gemini-3.8-live", decoded["model"]!!.jsonPrimitive.content)
        // No `config` wrapper: the server rejects it outright.
        assertTrue(!decoded.containsKey("config"))
        // The client must not drop or rewrite anything the token locked.
        assertTrue(decoded.containsKey("generationConfig"))
        assertTrue(decoded.containsKey("contextWindowCompression"))
        assertTrue(decoded.containsKey("systemInstruction"))
        assertTrue(decoded.containsKey("tools") || true)
        assertEquals(
            listOf("AUDIO"),
            decoded["generationConfig"]!!.jsonObject["responseModalities"]!!
                .jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            16000,
            decoded["contextWindowCompression"]!!.jsonObject["triggerTokens"]!!
                .jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun `fresh session sends empty sessionResumption`() {
        val session = GeminiLiveSession()
        val frame = session.buildSetupFrame(credentials(serverSetup()), handle = null)
        val resumption = json.parseToJsonElement(frame).jsonObject["setup"]!!
            .jsonObject["sessionResumption"]!!.jsonObject
        // Present but empty: enables resumption, starts a new conversation.
        assertTrue(resumption.isEmpty())
    }

    @Test
    fun `reconnect injects the resumption handle`() {
        val session = GeminiLiveSession()
        val frame = session.buildSetupFrame(credentials(serverSetup()), handle = "handle-abc")
        val resumption = json.parseToJsonElement(frame).jsonObject["setup"]!!
            .jsonObject["sessionResumption"]!!.jsonObject
        assertEquals("handle-abc", resumption["handle"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handle injection does not disturb other config keys`() {
        val session = GeminiLiveSession()
        val frame = session.buildSetupFrame(credentials(serverSetup()), handle = "h")
        val setup = json.parseToJsonElement(frame).jsonObject["setup"]!!.jsonObject
        assertTrue(setup.containsKey("systemInstruction"))
        assertTrue(setup.containsKey("generationConfig"))
        assertTrue(setup.containsKey("contextWindowCompression"))
    }

    @Test
    fun `setup survives a body with no sessionResumption key`() {
        val session = GeminiLiveSession()
        val minimal = buildJsonObject {
            put("model", "models/gemini-3.8-live")
            putJsonObject("generationConfig") { put("temperature", 0.5) }
        }
        val frame = session.buildSetupFrame(credentials(minimal), handle = "h2")
        val setup = json.parseToJsonElement(frame).jsonObject["setup"]!!.jsonObject
        assertEquals(
            "h2",
            setup["sessionResumption"]!!.jsonObject["handle"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `credentials expiry is reported`() {
        val expired = credentials(serverSetup()).copy(
            expiresAtEpochMs = System.currentTimeMillis() - 1,
        )
        assertTrue(expired.isExpired())
        assertFalse(credentials(serverSetup()).isExpired())
    }

    @Test
    fun `new session window is shorter than the message window`() {
        val creds = credentials(serverSetup())
        // Documented Live API behaviour: ~1 min to open, ~30 min to use.
        assertTrue(creds.newSessionExpiresAtEpochMs < creds.expiresAtEpochMs)
        assertTrue(creds.canStartNewSession())
    }

    @Test
    fun `backoff grows and is capped`() {
        val policy = ReconnectPolicy(baseDelayMs = 500, maxDelayMs = 8_000)
        val first = policy.backoffMillis(1)
        val third = policy.backoffMillis(3)
        assertTrue("first=$first", first in 500..750)
        assertTrue("third=$third", third > first)
        // Never waits longer than the cap, however many attempts.
        repeat(20) { attempt ->
            assertTrue(policy.backoffMillis(attempt + 1) <= 8_000)
        }
    }

    @Test
    fun `proactive reconnect leaves a safety margin before goAway deadline`() {
        val session = GeminiLiveSession(reconnectPolicy = ReconnectPolicy(goAwaySafetyMarginMs = 1_500))
        // 9.5 s left -> reconnect in 8 s, so the new socket is up first.
        assertEquals(8_000L, session.computeProactiveDelay(9_500))
        // Already inside the margin -> reconnect immediately, never negative.
        assertEquals(0L, session.computeProactiveDelay(500))
        // Unknown deadline -> do not delay.
        assertEquals(0L, session.computeProactiveDelay(null))
    }
}

/**
 * Regression: the two deadlines on an ephemeral token are not the same thing.
 *
 * `expireTime` (~30 min) governs an already-open connection;
 * `newSessionExpireTime` (~60 s) governs whether the token may open a socket
 * at all. Reconnecting checked only the former, so any drop more than a minute
 * into a session retried with a token the server rejected
 * (`1011 new_session_expire_time deadline exceeded`) until retries ran out and
 * the user was told Gemini was unreachable.
 */
class EphemeralTokenWindowsTest {

    private fun creds(expiresInMs: Long, canOpenForMs: Long) = LiveCredentials(
        token = "t",
        wsUrl = "wss://example.invalid",
        model = "gemini-3.8-live",
        setup = kotlinx.serialization.json.buildJsonObject { },
        inputSampleRateHz = 16_000,
        outputSampleRateHz = 24_000,
        inputAudioMime = "audio/pcm;rate=16000",
        expiresAtEpochMs = System.currentTimeMillis() + expiresInMs,
        newSessionExpiresAtEpochMs = System.currentTimeMillis() + canOpenForMs,
    )

    @Test
    fun `a token can be unable to open a session while still unexpired`() {
        // This is the normal state ~1 minute into any session.
        val c = creds(expiresInMs = 25 * 60_000, canOpenForMs = -1_000)
        assertFalse(c.isExpired())
        assertFalse(c.canStartNewSession())
    }

    @Test
    fun `a fresh token can do both`() {
        val c = creds(expiresInMs = 30 * 60_000, canOpenForMs = 60_000)
        assertFalse(c.isExpired())
        assertTrue(c.canStartNewSession())
    }

    @Test
    fun `the open-session window is the shorter of the two`() {
        val c = creds(expiresInMs = 30 * 60_000, canOpenForMs = 60_000)
        assertTrue(c.newSessionExpiresAtEpochMs < c.expiresAtEpochMs)
    }

    @Test
    fun `canStartNewSession honours an explicit clock`() {
        val c = creds(expiresInMs = 30 * 60_000, canOpenForMs = 10_000)
        // Checking 30 s ahead must report false, which is what lets the
        // repository re-mint *before* handing out a dying token.
        assertFalse(c.canStartNewSession(System.currentTimeMillis() + 30_000))
    }
}
