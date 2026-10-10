package com.fishmate.ai

import com.fishmate.ai.live.protocol.Blob
import com.fishmate.ai.live.protocol.ClientContent
import com.fishmate.ai.live.protocol.ClientContentFrame
import com.fishmate.ai.live.protocol.Content
import com.fishmate.ai.live.protocol.Part
import com.fishmate.ai.live.protocol.LiveJson
import com.fishmate.ai.live.protocol.RealtimeInput
import com.fishmate.ai.live.protocol.RealtimeInputFrame
import com.fishmate.ai.live.protocol.ServerMessage
import com.fishmate.ai.live.protocol.ToolResponse
import com.fishmate.ai.live.protocol.ToolResponseFrame
import com.fishmate.ai.live.protocol.FunctionResponse
import com.fishmate.ai.live.protocol.parseDurationMillis
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-format tests.
 *
 * These matter more than they look: the Live API silently ignores fields it
 * does not recognise, so a misspelled key produces an assistant that simply
 * never hears anything, with no error anywhere. Encoding is asserted against
 * literal JSON rather than round-tripping our own classes.
 */
class ProtocolTest {

    @Test
    fun `audio frame uses realtimeInput audio with mime type`() {
        val json = LiveJson.instance.encodeToString(
            RealtimeInputFrame.serializer(),
            RealtimeInputFrame(
                RealtimeInput(audio = Blob("AAAA", "audio/pcm;rate=16000")),
            ),
        )
        assertTrue(json, json.contains("\"realtimeInput\""))
        assertTrue(json, json.contains("\"audio\""))
        assertTrue(json, json.contains("\"mimeType\":\"audio/pcm;rate=16000\""))
        assertTrue(json, json.contains("\"data\":\"AAAA\""))
        // Absent fields must not be serialised as null.
        assertTrue(json, !json.contains("\"video\""))
        assertTrue(json, !json.contains("null"))
    }

    @Test
    fun `video frame uses realtimeInput video with jpeg mime type`() {
        val json = LiveJson.instance.encodeToString(
            RealtimeInputFrame.serializer(),
            RealtimeInputFrame(RealtimeInput(video = Blob("/9j/", "image/jpeg"))),
        )
        assertTrue(json, json.contains("\"video\""))
        assertTrue(json, json.contains("\"mimeType\":\"image/jpeg\""))
    }

    @Test
    fun `audio stream end is a boolean flag`() {
        val json = LiveJson.instance.encodeToString(
            RealtimeInputFrame.serializer(),
            RealtimeInputFrame(RealtimeInput(audioStreamEnd = true)),
        )
        assertTrue(json, json.contains("\"audioStreamEnd\":true"))
    }

    @Test
    fun `tool response carries id name and response`() {
        val json = LiveJson.instance.encodeToString(
            ToolResponseFrame.serializer(),
            ToolResponseFrame(
                ToolResponse(
                    listOf(
                        FunctionResponse(
                            id = "call-7",
                            name = "get_current_conditions",
                            response = buildJsonObject { put("summary", "calm") },
                        ),
                    ),
                ),
            ),
        )
        assertTrue(json, json.contains("\"toolResponse\""))
        assertTrue(json, json.contains("\"functionResponses\""))
        assertTrue(json, json.contains("\"id\":\"call-7\""))
        assertTrue(json, json.contains("\"name\":\"get_current_conditions\""))
    }

    @Test
    fun `decodes setupComplete`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"setupComplete":{}}""",
        )
        assertNotNull(message.setupComplete)
    }

    @Test
    fun `decodes model audio turn`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"serverContent":{"modelTurn":{"parts":[
              {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QUJD"}}
            ]}}}
            """.trimIndent(),
        )
        val part = message.serverContent?.modelTurn?.parts?.first()
        assertEquals("QUJD", part?.inlineData?.data)
    }

    @Test
    fun `decodes interruption and turn flags`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"serverContent":{"interrupted":true,"turnComplete":true,"generationComplete":true}}""",
        )
        assertEquals(true, message.serverContent?.interrupted)
        assertEquals(true, message.serverContent?.turnComplete)
        assertEquals(true, message.serverContent?.generationComplete)
    }

    @Test
    fun `decodes transcriptions in both directions`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"serverContent":{
              "inputTranscription":{"text":"ne kullanayim"},
              "outputTranscription":{"text":"kucuk sahteyle devam et"}
            }}
            """.trimIndent(),
        )
        assertEquals("ne kullanayim", message.serverContent?.inputTranscription?.text)
        assertEquals(
            "kucuk sahteyle devam et",
            message.serverContent?.outputTranscription?.text,
        )
    }

    @Test
    fun `decodes tool call with arguments`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"toolCall":{"functionCalls":[
              {"id":"c1","name":"log_catch","args":{"species":"levrek","length_cm":42}}
            ]}}
            """.trimIndent(),
        )
        val call = message.toolCall?.functionCalls?.single()
        assertEquals("log_catch", call?.name)
        assertEquals("c1", call?.id)
        // Snake_case argument names must survive: the backend validates them.
        assertTrue(call?.args?.containsKey("length_cm") == true)
    }

    @Test
    fun `decodes goAway and session resumption update`() {
        val goAway = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"goAway":{"timeLeft":"9.5s"}}""",
        )
        assertEquals("9.5s", goAway.goAway?.timeLeft)

        val resumption = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"sessionResumptionUpdate":{"newHandle":"handle-xyz","resumable":true}}""",
        )
        assertEquals("handle-xyz", resumption.sessionResumptionUpdate?.newHandle)
    }

    @Test
    fun `tolerates unknown server fields`() {
        // A new API field must not kill a live conversation.
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"setupComplete":{},"somethingBrandNew":{"x":1},"serverContent":{"turnComplete":true}}""",
        )
        assertNotNull(message.setupComplete)
        assertEquals(true, message.serverContent?.turnComplete)
    }

    @Test
    fun `parses protobuf duration strings`() {
        assertEquals(9500L, parseDurationMillis("9.5s"))
        assertEquals(1000L, parseDurationMillis("1s"))
        assertEquals(90_000L, parseDurationMillis("1m30s"))
        assertNull(parseDurationMillis(null))
        assertNull(parseDurationMillis(""))
        assertNull(parseDurationMillis("garbage"))
    }
}

/**
 * Server-event fields added during the Live API compliance audit.
 *
 * The API reference lists more fields on `serverContent` than an obvious
 * implementation handles, and the ones below were being silently dropped. Each
 * is now modelled and asserted, because a dropped field is invisible at
 * runtime — nothing errors, a capability just quietly does not work.
 */
class ServerEventCoverageTest {

    @Test
    fun `decodes interim input transcription`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"serverContent":{"interimInputTranscription":{"text":"ne kulla"}}}""",
        )
        assertEquals("ne kulla", message.serverContent?.interimInputTranscription?.text)
    }

    @Test
    fun `interim and final transcription are distinct fields`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"serverContent":{
              "interimInputTranscription":{"text":"ne kulla"},
              "inputTranscription":{"text":"ne kullanayim"}
            }}
            """.trimIndent(),
        )
        assertEquals("ne kulla", message.serverContent?.interimInputTranscription?.text)
        assertEquals("ne kullanayim", message.serverContent?.inputTranscription?.text)
    }

    @Test
    fun `decodes grounding metadata with web sources`() {
        // This is the evidence that the built-in Google Search tool ran.
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"serverContent":{"groundingMetadata":{
              "webSearchQueries":["levrek asgari boy"],
              "groundingChunks":[
                {"web":{"uri":"https://tarimorman.gov.tr/x","title":"Teblig"}},
                {"web":{"uri":"https://example.gov/y"}}
              ]
            }}}
            """.trimIndent(),
        )
        val grounding = message.serverContent?.groundingMetadata
        assertEquals(listOf("levrek asgari boy"), grounding?.webSearchQueries)
        assertEquals(2, grounding?.groundingChunks?.size)
        assertEquals("https://tarimorman.gov.tr/x", grounding?.groundingChunks?.first()?.web?.uri)
        assertEquals("Teblig", grounding?.groundingChunks?.first()?.web?.title)
        // A chunk with no title must still parse.
        assertNull(grounding?.groundingChunks?.get(1)?.web?.title)
    }

    @Test
    fun `decodes waiting for input`() {
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """{"serverContent":{"waitingForInput":true}}""",
        )
        assertEquals(true, message.serverContent?.waitingForInput)
    }

    @Test
    fun `one event can carry several independent signals`() {
        // The API does not guarantee one signal per event, so the handler must
        // not treat these as mutually exclusive.
        val message = LiveJson.instance.decodeFromString(
            ServerMessage.serializer(),
            """
            {"serverContent":{
               "modelTurn":{"parts":[
                 {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"QQ=="}},
                 {"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"Qg=="}},
                 {"text":"ek bilgi"}
               ]},
               "outputTranscription":{"text":"kucuk sahte"},
               "generationComplete":true,
               "turnComplete":true
             },
             "usageMetadata":{"totalTokenCount":42}}
            """.trimIndent(),
        )
        // Three parts in one event, not one.
        assertEquals(3, message.serverContent?.modelTurn?.parts?.size)
        assertEquals("kucuk sahte", message.serverContent?.outputTranscription?.text)
        assertEquals(true, message.serverContent?.generationComplete)
        assertEquals(42, message.usageMetadata?.totalTokenCount)
    }

    @Test
    fun `client content frame primes context without completing the turn`() {
        // turnComplete=true would unconditionally interrupt generation.
        val json = LiveJson.instance.encodeToString(
            ClientContentFrame.serializer(),
            ClientContentFrame(
                ClientContent(
                    turns = listOf(Content(role = "user", parts = listOf(Part(text = "ctx")))),
                    turnComplete = false,
                ),
            ),
        )
        assertTrue(json, json.contains("\"clientContent\""))
        assertTrue(json, json.contains("\"turns\""))
        // The role must survive serialisation: `clientContent` is documented
        // as using explicit user/model roles.
        assertTrue(json, json.contains("\"role\":\"user\""))
        // Explicitly false, never true — true would interrupt generation.
        assertTrue(json, json.contains("\"turnComplete\":false"))
        assertTrue(json, !json.contains("\"turnComplete\":true"))
    }

    @Test
    fun `tool response carries scheduling only when set`() {
        val withScheduling = LiveJson.instance.encodeToString(
            ToolResponseFrame.serializer(),
            ToolResponseFrame(
                ToolResponse(
                    listOf(
                        FunctionResponse(
                            id = "c1",
                            name = "get_fishing_regulations",
                            response = buildJsonObject { put("verified", false) },
                            scheduling = "WHEN_IDLE",
                        ),
                    ),
                ),
            ),
        )
        assertTrue(withScheduling, withScheduling.contains("\"scheduling\":\"WHEN_IDLE\""))

        val blocking = LiveJson.instance.encodeToString(
            ToolResponseFrame.serializer(),
            ToolResponseFrame(
                ToolResponse(
                    listOf(
                        FunctionResponse(
                            id = "c2",
                            name = "get_current_conditions",
                            response = buildJsonObject { put("ok", true) },
                        ),
                    ),
                ),
            ),
        )
        // Omitted for ordinary blocking tools, where it has no meaning.
        assertTrue(blocking, !blocking.contains("scheduling"))
    }
}


/** Regression: roles must reach the wire for mixed-role context priming. */
class ClientContentRoleTest {

    @Test
    fun `both user and model roles are serialised`() {
        val json = LiveJson.instance.encodeToString(
            ClientContentFrame.serializer(),
            ClientContentFrame(
                ClientContent(
                    turns = listOf(
                        Content(role = "user", parts = listOf(Part(text = "Ne tuttum?"))),
                        Content(role = "model", parts = listOf(Part(text = "Levrek."))),
                    ),
                    turnComplete = false,
                ),
            ),
        )
        assertTrue(json, json.contains("\"role\":\"user\""))
        assertTrue(json, json.contains("\"role\":\"model\""))
    }

    @Test
    fun `asking for a reply really puts turnComplete true on the wire`() {
        // The opening greeting is the one frame that must request generation,
        // and it silently stopped doing so. `turnComplete` used to default to
        // `true` while `LiveJson` sets `encodeDefaults = false`, so passing
        // `true` produced a value *equal to the default* and the field was
        // dropped from the JSON entirely. The server read the frame as
        // `turnComplete: false`: it took the context and generated nothing,
        // while the client logged a successful send and the object in memory
        // was perfectly correct. Only the serialised bytes show it, which is
        // why this asserts on the string.
        val json = LiveJson.instance.encodeToString(
            ClientContentFrame.serializer(),
            ClientContentFrame(
                ClientContent(
                    turns = listOf(Content(role = "user", parts = listOf(Part(text = "hi")))),
                    turnComplete = true,
                ),
            ),
        )
        assertTrue(json, json.contains("\"turnComplete\":true"))
    }
}
