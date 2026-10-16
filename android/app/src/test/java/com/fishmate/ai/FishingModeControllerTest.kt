package com.fishmate.ai

import app.cash.turbine.test
import com.fishmate.ai.audio.AudioCapture
import com.fishmate.ai.audio.AudioRoute
import com.fishmate.ai.audio.AudioPlayer
import com.fishmate.ai.audio.AudioRouting
import com.fishmate.ai.camera.FrameConfig
import com.fishmate.ai.camera.CameraState
import com.fishmate.ai.camera.CameraSource
import com.fishmate.ai.camera.CameraFrame
import com.fishmate.ai.camera.CameraFacing
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.repository.LiveCredentialsRepository
import com.fishmate.ai.live.AssistantPhase
import com.fishmate.ai.live.FishingModeController
import com.fishmate.ai.live.LiveConnectionState
import com.fishmate.ai.live.LiveCredentials
import com.fishmate.ai.live.LiveEvent
import com.fishmate.ai.live.MockLiveAiSession
import com.fishmate.ai.live.ToolOrchestrator
import com.fishmate.ai.live.ToolCallRequest
import com.fishmate.ai.live.ToolExecutionContext
import com.fishmate.ai.live.ToolResult
import com.fishmate.ai.location.FakeLocationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Fishing Mode state machine.
 *
 * Driven entirely through [MockLiveAiSession], so interruption, tool calls,
 * reconnection and token renewal are all exercised without touching Gemini.
 * These are the behaviours that make the product feel conversational, and they
 * are exactly the ones that are impractical to test against the live service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FishingModeControllerTest {

    private fun credentials() = LiveCredentials(
        token = "t",
        wsUrl = "wss://example.invalid",
        model = "gemini-3.8-live",
        setup = buildJsonObject { put("model", "gemini-3.8-live") },
        inputSampleRateHz = 16_000,
        outputSampleRateHz = 24_000,
        inputAudioMime = "audio/pcm;rate=16000",
        expiresAtEpochMs = System.currentTimeMillis() + 1_800_000,
        newSessionExpiresAtEpochMs = System.currentTimeMillis() + 60_000,
    )

    /** A camera that streams on demand, so "the model asked to see" is testable. */
    private class FakeCameraSource(
        private val failToStream: Boolean = false,
    ) : CameraSource {
        override val state = MutableStateFlow<CameraState>(CameraState.Idle)
        override val facing = MutableStateFlow(CameraFacing.BACK)
        override val supportsSwitching = true
        var released = false
            private set

        override fun frames(config: FrameConfig): Flow<CameraFrame> = flow {
            if (failToStream) throw IllegalStateException("camera unavailable")
            // Bounded, then parked. Under a TestScope `delay` costs no real
            // time, so an endless camera would emit until the heap gave out —
            // which it did, as an OutOfMemoryError rather than a test failure.
            repeat(FRAMES) { n ->
                emit(CameraFrame(byteArrayOf(1, 2, 3, n.toByte()), 64, 64))
                delay(config.intervalMillis.coerceAtMost(200))
            }
            awaitCancellation()
        }

        // Mirrors the real source: only a streamed frame can be reused, so this
        // is null until frames() has run.
        override suspend fun captureStill(config: FrameConfig): CameraFrame? = null

        override suspend fun switchCamera() {
            facing.value =
                if (facing.value == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        }

        override fun release() {
            released = true
        }

        private companion object {
            const val FRAMES = 5
        }
    }

    private class Harness(
        val toolResponder: (List<ToolCallRequest>) -> List<ToolResult> = { calls ->
            calls.map { ToolResult(it.id, it.name, buildJsonObject { put("ok", true) }) }
        },
        val camera: FakeCameraSource? = null,
        credentialsResult: () -> AppResult<LiveCredentials>,
    ) {
        val session = MockLiveAiSession()
        val audioPlayer: AudioPlayer = mockk(relaxed = true)
        val audioCapture: AudioCapture = mockk(relaxed = true)
        val audioRouting: AudioRouting = mockk(relaxed = true)
        val credentialsRepo: LiveCredentialsRepository = mockk(relaxed = true)
        val orchestrator: ToolOrchestrator = mockk()
        val location = FakeLocationProvider()
        val scope = TestScope(StandardTestDispatcher())

        val controller: FishingModeController

        /** Drives the echo guard; see `playing` on the real AudioPlayer. */
        val playing = MutableStateFlow(false)

        /** Stands in for [AudioPlayer.isAudible]: is the speaker still sounding? */
        var audible = false

        /**
         * The two capture callbacks the controller installs. A relaxed mock
         * does not retain property writes, so they are recorded here the same
         * way `suppressWhileSpeaking` is.
         */
        var speakerLevelProvider: () -> Float = { 0f }
            private set
        var bargeInCallback: () -> Unit = {}
            private set

        /** Stands in for the gate's latch. */
        var bargeInOpen = false

        /**
         * Whether the echo guard currently has the microphone gated. A relaxed
         * mock does not retain property writes, so the setter is recorded here.
         */
        var suppressed = false
            private set

        /** Speaker by default, which is the route the echo guard acts on. */
        val route = MutableStateFlow(AudioRoute.SPEAKER)

        init {
            every { audioCapture.chunks() } returns emptyFlow()
            every { audioRouting.acquireFocus() } returns true
            // Real flows, not relaxed mocks: a mocked StateFlow cannot be
            // collected, and the echo guard collects both of these.
            every { audioPlayer.playing } returns playing
            every { audioPlayer.isAudible } answers { audible }
            every { audioRouting.route } returns route
            every { audioCapture.suppressWhileSpeaking = any() } answers {
                suppressed = firstArg()
            }
            every { audioCapture.suppressWhileSpeaking } answers { suppressed }
            every { audioCapture.speakerLevel = any() } answers { speakerLevelProvider = firstArg() }
            every { audioCapture.speakerLevel } answers { speakerLevelProvider }
            every { audioCapture.onBargeIn = any() } answers { bargeInCallback = firstArg() }
            every { audioCapture.onBargeIn } answers { bargeInCallback }
            every { audioCapture.isBargeInOpen } answers { bargeInOpen }
            coEvery {
                credentialsRepo.current(any(), any(), any(), any(), any())
            } answers { credentialsResult() }
            coEvery { orchestrator.execute(any(), any()) } answers {
                toolResponder(firstArg())
            }
            controller = FishingModeController(
                session = session,
                credentials = credentialsRepo,
                toolOrchestrator = orchestrator,
                audioCapture = audioCapture,
                audioPlayer = audioPlayer,
                audioRouting = audioRouting,
                locationProvider = location,
                scope = scope,
                cameraSourceProvider = { camera },
            )
        }
    }

    @Test
    fun `a fresh session is thinking, not listening, while it composes the greeting`() = runTest {
        // "Listening -- ask me anything" invites the angler to talk into a
        // turn that is already being generated, and whatever they say barges
        // in and cancels the hello.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start(language = "tr", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        assertEquals(1, h.session.connectCount)
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()

        assertEquals(AssistantPhase.THINKING, h.controller.state.value.phase)
    }

    @Test
    fun `the microphone stays shut until the greeting is audible`() = runTest {
        // Server VAD interrupts on any activity, so a mic opened before the
        // first audio chunk lets a gust of wind cancel the greeting. The
        // window is short but it is exactly when the phone is being taken out
        // of a pocket.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start(language = "tr", cameraEnabled = false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()

        verify(exactly = 0) { h.audioCapture.chunks() }

        h.session.emitAudio(ByteArray(320))
        h.scope.advanceUntilIdle()

        verify { h.audioCapture.chunks() }
    }

    @Test
    fun `a silent socket is asked for the greeting a second time`() = runTest {
        // Observed on the device: setup completed, the priming was sent, and
        // the model produced no audio, no tool call and no error. The same
        // two turns answered normally against the service a minute later, so
        // the session must not be left silent on one flake.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()
        assertEquals(1, h.session.openingTurnsRequested)

        // The first ask times out.
        h.scope.testScheduler.advanceTimeBy(6_500)
        h.scope.testScheduler.runCurrent()

        assertEquals("the greeting must be asked for again", 2, h.session.openingTurnsRequested)
    }

    @Test
    fun `the microphone opens anyway if no greeting arrives`() = runTest {
        // A model that says nothing must not leave the angler holding a dead
        // microphone, so the wait is bounded.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start(language = "tr", cameraEnabled = false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        verify { h.audioCapture.chunks() }
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `missing credentials surfaces a user message and releases audio focus`() = runTest {
        val error = com.fishmate.ai.core.AppError(
            "live_token_unavailable",
            "I can't reach Gemini right now.",
        )
        val h = Harness(credentialsResult = { AppResult.Err(error) })
        h.controller.start(language = "tr", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        assertEquals(AssistantPhase.ERROR, h.controller.state.value.phase)
        assertEquals("I can't reach Gemini right now.", h.controller.state.value.errorMessage)
        // The microphone must not be held open after a failed start.
        verify { h.audioRouting.releaseFocus() }
        assertEquals(0, h.session.connectCount)
    }

    @Test
    fun `microphone is suppressed while speaking on the loudspeaker`() = runTest {
        // The echo loop this prevents was measured on a real phone at one
        // interruption every three seconds: the model heard its own voice
        // through the speaker, treated it as a barge-in, restarted, and heard
        // itself again.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        h.playing.value = true
        h.scope.advanceUntilIdle()
        assertTrue(h.suppressed)
    }

    @Test
    fun `suppression lifts after the assistant stops, with a tail`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.playing.value = true
        h.scope.advanceUntilIdle()

        h.playing.value = false
        // Still suppressed immediately afterwards: the AudioTrack buffer and
        // the room are both still carrying the last syllable.
        h.scope.testScheduler.advanceTimeBy(100)
        assertTrue(h.suppressed)

        h.scope.advanceUntilIdle()
        assertFalse(h.suppressed)
    }

    @Test
    fun `no suppression on a headset, so barge-in stays exact`() = runTest {
        // With earbuds there is no acoustic path from the speaker back to the
        // microphone, so gating would only cost responsiveness.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.route.value = AudioRoute.BLUETOOTH
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        h.playing.value = true
        h.scope.advanceUntilIdle()
        assertFalse(h.suppressed)
    }

    @Test
    fun `translation is attached to the line it was requested for`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitTranscript(user = "the water is very clear today")
        h.scope.advanceUntilIdle()

        val line = h.controller.state.value.transcript.first()
        h.controller.applyTranslation(line.id, line.text, "su bugün çok berrak")
        assertEquals("su bugün çok berrak", h.controller.state.value.transcript.first().translation)
    }

    @Test
    fun `a translation for a stale version of a line is discarded`() = runTest {
        // Transcription arrives as fragments, so a line keeps growing while
        // someone is speaking. A translation that comes back for the shorter
        // sentence would be a subtitle for something the angler never said.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitTranscript(user = "the water is")
        h.scope.advanceUntilIdle()
        val stale = h.controller.state.value.transcript.first()

        h.session.emitTranscript(user = " very clear today")
        h.scope.advanceUntilIdle()

        h.controller.applyTranslation(stale.id, stale.text, "su")
        assertNull(h.controller.state.value.transcript.first().translation)
    }

    @Test
    fun `audio chunks are enqueued and move to speaking`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitAudio(byteArrayOf(1, 2, 3, 4))
        h.scope.advanceUntilIdle()

        assertEquals(AssistantPhase.SPEAKING, h.controller.state.value.phase)
        verify { h.audioPlayer.enqueue(any()) }
    }

    @Test
    fun `interruption flushes playback immediately`() = runTest {
        // Barge-in: if queued audio is not dropped, the model keeps talking
        // over the angler for a second or more and it feels broken.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitAudio(byteArrayOf(1, 2))
        h.scope.advanceUntilIdle()

        h.session.emitInterruption()
        h.scope.advanceUntilIdle()

        verify { h.audioPlayer.flush() }
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
        assertEquals(1, h.controller.state.value.interruptions)
    }

    @Test
    fun `tool calls are executed and answered`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.session.emitToolCall(
            "get_current_conditions",
            buildJsonObject { put("latitude", 38.46) },
        )
        h.scope.advanceUntilIdle()

        assertEquals(1, h.session.sentToolResults.size)
        assertEquals("get_current_conditions", h.session.sentToolResults.first().first().name)
        // Tool activity is recorded so the UI can show it is consulting data.
        assertTrue("get_current_conditions" in h.controller.state.value.toolActivity)
    }

    @Test
    fun `tool call ids are echoed back so the server can match them`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.session.emitToolCall("log_catch", buildJsonObject { put("species", "levrek") }, id = "call-99")
        h.scope.advanceUntilIdle()

        assertEquals("call-99", h.session.sentToolResults.first().first().id)
    }

    @Test
    fun `tool execution receives the device position`() = runTest {
        val captured = mutableListOf<ToolExecutionContext>()
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        coEvery { h.orchestrator.execute(any(), any()) } answers {
            captured += secondArg<ToolExecutionContext>()
            emptyList<ToolResult>()
        }
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitToolCall("get_current_conditions", buildJsonObject { })
        h.scope.advanceUntilIdle()

        assertEquals(38.46, captured.single().latitude!!, 0.001)
        assertEquals("tr", captured.single().language)
    }

    @Test
    fun `resumption handle is stored for reconnects`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.session.emitResumptionHandle("handle-42")
        h.scope.advanceUntilIdle()

        assertEquals("handle-42", h.controller.state.value.resumeHandle)
    }

    @Test
    fun `reconnecting shows as reconnecting not as an error`() = runTest {
        // The brief is explicit: the user should see "Reconnecting…", because
        // the server rotates the connection roughly every ten minutes.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.session.setState(LiveConnectionState.Reconnecting(1, "connection lost"))
        h.scope.advanceUntilIdle()

        assertEquals(AssistantPhase.RECONNECTING, h.controller.state.value.phase)
        assertEquals("reconnecting", h.controller.state.value.statusKey)
        assertTrue(h.controller.state.value.isReconnecting)
        // Stale audio from the dead socket is dropped.
        verify { h.audioPlayer.flush() }
    }

    @Test
    fun `expired token triggers a re-mint and resumes with the handle`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitResumptionHandle("handle-live")
        h.scope.advanceUntilIdle()

        h.session.emitEvent(
            LiveEvent.Error("token_expired", "Live credentials expired.", fatal = true),
        )
        h.scope.advanceUntilIdle()

        // Reconnected (second connect) carrying the conversation forward.
        assertEquals(2, h.session.connectCount)
        assertEquals("handle-live", h.session.lastResumeHandle)
    }

    @Test
    fun `mute signals end of audio stream and updates state`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.controller.setMuted(true)
        h.scope.advanceUntilIdle()

        assertTrue(h.controller.state.value.isMuted)
        assertEquals("muted", h.controller.state.value.statusKey)
        // Telling the server the turn ended stops it waiting for more speech.
        assertEquals(1, h.session.audioStreamEndCount)
    }

    @Test
    fun `unmute resumes capture`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.controller.setMuted(true)
        h.controller.setMuted(false)
        h.scope.advanceUntilIdle()
        assertFalse(h.controller.state.value.isMuted)
    }

    @Test
    fun `transcripts accumulate into the on-screen transcript`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.session.emitTranscript(user = "Ne kullanayim?")
        h.session.emitTranscript(model = "Kucuk sahteyle devam et.")
        h.scope.advanceUntilIdle()

        val transcript = h.controller.state.value.transcript
        assertEquals(2, transcript.size)
        assertEquals("Ne kullanayim?", transcript.first().text)
    }

    @Test
    fun `stop releases audio focus and disconnects`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.controller.stop()
        h.scope.advanceUntilIdle()

        assertEquals(LiveConnectionState.Closed, h.session.connectionState.value)
        verify { h.audioRouting.releaseFocus() }
        verify { h.audioPlayer.stop() }
    }

    @Test
    fun `turn complete returns to listening`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitAudio(byteArrayOf(9))
        h.session.emitEvent(LiveEvent.TurnComplete)
        h.scope.advanceUntilIdle()
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `usage metadata is tracked for cost visibility`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Usage(1234))
        h.scope.advanceUntilIdle()
        assertEquals(1234, h.controller.state.value.totalTokens)
    }

    // ---- repeat counting -------------------------------------------------
    // The counter is incremental because it runs on the main thread, which
    // means the bookkeeping — not the sentence matching — is where a bug would
    // hide. These drive it through real transcript events.

    @Test
    fun `a sentence the assistant restarts mid-turn is counted`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        // Fragments, the way transcription actually arrives.
        h.session.emitEvent(LiveEvent.OutputTranscript("Try casting along "))
        h.session.emitEvent(LiveEvent.OutputTranscript("the rocks. "))
        h.session.emitEvent(LiveEvent.OutputTranscript("Try casting along the rocks."))
        h.scope.advanceUntilIdle()

        assertEquals(1, h.controller.state.value.repeatedSentences)
    }

    @Test
    fun `the count is cumulative across turns and a clean turn never lowers it`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(
            LiveEvent.OutputTranscript("Work it near the bottom. Work it near the bottom."),
        )
        h.scope.advanceUntilIdle()
        assertEquals(1, h.controller.state.value.repeatedSentences)

        // A new turn: the angler speaks, then a clean answer.
        h.session.emitEvent(LiveEvent.InputTranscript("What about the leader?"))
        h.session.emitEvent(LiveEvent.OutputTranscript("Drop to a lighter fluorocarbon leader."))
        h.scope.advanceUntilIdle()

        assertEquals(
            "an earlier loop must not be forgotten when the next turn is clean",
            1,
            h.controller.state.value.repeatedSentences,
        )
    }

    @Test
    fun `repeats in consecutive turns add up`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(
            LiveEvent.OutputTranscript("Work it near the bottom. Work it near the bottom."),
        )
        h.session.emitEvent(LiveEvent.InputTranscript("And now?"))
        h.session.emitEvent(
            LiveEvent.OutputTranscript("Speed up the retrieve a little. Speed up the retrieve a little."),
        )
        h.scope.advanceUntilIdle()

        assertEquals(2, h.controller.state.value.repeatedSentences)
    }

    @Test
    fun `the angler repeating themselves is not counted`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(
            LiveEvent.InputTranscript("Say that again please. Say that again please."),
        )
        h.scope.advanceUntilIdle()

        assertEquals(0, h.controller.state.value.repeatedSentences)
    }

    // ---- the model opening its own eyes ----------------------------------
    // The camera starts off, so "do you see this?" only works if the model can
    // turn it on itself. The contract that matters: the tool call is answered
    // only once a frame has actually gone out, and a failure comes back as a
    // failure — a model told "camera on" that sees nothing would describe a
    // fish that is not there.

    private fun setCameraCall(enabled: Boolean, id: String = "c1") = ToolCallRequest(
        id = id,
        name = "set_camera",
        args = buildJsonObject { put("enabled", enabled) },
    )

    @Test
    fun `set_camera turns the camera on and answers only after a frame is sent`() = runTest {
        val h = Harness(camera = FakeCameraSource(), credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()
        assertFalse(h.controller.state.value.cameraEnabled)

        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(true))))
        h.scope.advanceUntilIdle()

        assertTrue("camera should be on", h.controller.state.value.cameraEnabled)
        assertTrue("a frame must reach the model", h.session.sentImages.isNotEmpty())
        val result = h.session.sentToolResults.single().single()
        assertEquals("set_camera", result.name)
        assertEquals("on", result.response["camera"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set_camera is never posted to the backend`() = runTest {
        // It acts on hardware the server cannot reach; posting it would just
        // come back as an error and cost a round-trip.
        val h = Harness(camera = FakeCameraSource(), credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(true))))
        h.scope.advanceUntilIdle()

        coVerify(exactly = 0) { h.orchestrator.execute(any(), any()) }
    }

    @Test
    fun `set_camera false stops watching`() = runTest {
        val h = Harness(camera = FakeCameraSource(), credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = true)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(false))))
        h.scope.advanceUntilIdle()

        assertFalse(h.controller.state.value.cameraEnabled)
        assertFalse(h.controller.state.value.isWatching)
        val result = h.session.sentToolResults.single().single()
        assertEquals("off", result.response["camera"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a camera that cannot stream is reported as a failure, not as sight`() = runTest {
        val h = Harness(
            camera = FakeCameraSource(failToStream = true),
            credentialsResult = { AppResult.Ok(credentials()) },
        )
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(true))))
        h.scope.advanceUntilIdle()

        val result = h.session.sentToolResults.single().single()
        assertEquals("device_tool_failed", result.response["error"]?.jsonPrimitive?.content)
        val instruction = result.response["instruction_to_model"]?.jsonPrimitive?.content ?: ""
        assertTrue(
            "the model must be told not to describe anything: $instruction",
            instruction.contains("not describe", ignoreCase = true),
        )
        assertFalse(h.controller.state.value.cameraEnabled)
    }

    @Test
    fun `no camera attached fails the call instead of leaving it unanswered`() = runTest {
        // An unanswered tool call hangs the conversation permanently, so this
        // is the worst possible way to handle a refused permission.
        val h = Harness(camera = null, credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(true))))
        h.scope.advanceUntilIdle()

        val result = h.session.sentToolResults.single().single()
        assertEquals("device_tool_failed", result.response["error"]?.jsonPrimitive?.content)
    }

    @Test
    fun `set_camera without a usable enabled flag fails cleanly`() = runTest {
        val h = Harness(camera = FakeCameraSource(), credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(
            LiveEvent.ToolCalls(
                listOf(ToolCallRequest(id = "c9", name = "set_camera", args = buildJsonObject {})),
            ),
        )
        h.scope.advanceUntilIdle()

        val result = h.session.sentToolResults.single().single()
        assertEquals("device_tool_failed", result.response["error"]?.jsonPrimitive?.content)
        assertFalse(h.controller.state.value.cameraEnabled)
    }

    @Test
    fun `a turn mixing a device tool and a backend tool answers both, in order`() = runTest {
        val h = Harness(camera = FakeCameraSource(), credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", cameraEnabled = false)
        h.scope.advanceUntilIdle()

        val weather = ToolCallRequest(id = "w1", name = "get_current_conditions", args = null)
        h.session.emitEvent(LiveEvent.ToolCalls(listOf(setCameraCall(true), weather)))
        h.scope.advanceUntilIdle()

        val results = h.session.sentToolResults.single()
        assertEquals(2, results.size)
        assertEquals(listOf("set_camera", "get_current_conditions"), results.map { it.name })
        // Only the backend tool was posted.
        coVerify(exactly = 1) {
            h.orchestrator.execute(
                match { posted -> posted.map { it.name } == listOf("get_current_conditions") },
                any(),
            )
        }
    }

    // ---- opening the session ---------------------------------------------
    // The complaint this answers: the assistant used to open by asking "where
    // are you fishing?" — a question the phone already had the answer to.

    @Test
    fun `the device position is sent when minting a token, so the spot can be named`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()

        coVerify {
            h.credentialsRepo.current(
                "en",
                any(),
                any(),
                match { lat -> lat != null && kotlin.math.abs(lat - 38.46) < 0.001 },
                match { lon -> lon != null && kotlin.math.abs(lon - 27.09) < 0.001 },
            )
        }
    }

    @Test
    fun `the assistant is asked to speak first on a fresh session`() = runTest {
        // A voice-first product that waits to be spoken to feels inert.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        // Stop short of the greeting timeout: past it the session asks a
        // second time, which `a silent socket is asked for the greeting a
        // second time` covers. This one is about asking at all.
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()

        assertEquals(1, h.session.openingTurnsRequested)
    }

    @Test
    fun `the opening carries the spot and the clock, and never asks for them`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        every { h.credentialsRepo.lastUserBrief } returns buildJsonObject {
            put("display_name", "Özgür")
            putJsonObject("place") {
                put("name", "Bostanlı Mahallesi")
                put("region", "İzmir")
            }
        }
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        val primed = h.session.primedContext.flatten().joinToString("\n") { it.text }
        assertTrue("the spot must be in the context: $primed", primed.contains("Bostanlı"))
        assertTrue("the region helps disambiguate: $primed", primed.contains("İzmir"))
        assertTrue("a clock reading must be present: $primed", Regex("\\d{2}:\\d{2}").containsMatchIn(primed))
        assertTrue("it must be told to speak first: $primed", primed.contains("Speak first"))
        assertTrue(
            "it must be told not to ask where they are: $primed",
            primed.contains("Do not ask where they are"),
        )
        // And then it has to get out of the way. The directive used to end
        // with "ask to see the water", which is wrong in half of all
        // sessions: the same angler opens this app at home to plan tomorrow,
        // where there is no water to show. An opening that proposes the next
        // move is definitely wrong in one of the two cases, and their first
        // sentence tells us which for free.
        assertTrue(
            "it must be told to stop and wait: $primed",
            primed.contains("stop and wait"),
        )
        assertTrue(
            "the opening must be told not to ask to see the water: $primed",
            primed.contains("do not ask to see the water"),
        )
        assertTrue(
            "the opening must be told not to name tackle: $primed",
            primed.contains("do not name any tackle"),
        )
    }

    @Test
    fun `the opening follows the session language`() = runTest {
        // It used to be hard-coded Turkish, so an English session was primed
        // in a language it was told never to mix into its answers.
        val english = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        english.controller.start("en", false)
        english.scope.advanceUntilIdle()
        english.session.emitEvent(LiveEvent.Ready)
        english.scope.advanceUntilIdle()
        val englishContext = english.session.primedContext.flatten().joinToString("\n") { it.text }
        assertTrue(englishContext, englishContext.contains("[Session context]"))
        assertFalse(englishContext, englishContext.contains("Oturum bağlamı"))

        val turkish = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        turkish.controller.start("tr", false)
        turkish.scope.advanceUntilIdle()
        turkish.session.emitEvent(LiveEvent.Ready)
        turkish.scope.advanceUntilIdle()
        val turkishContext = turkish.session.primedContext.flatten().joinToString("\n") { it.text }
        assertTrue(turkishContext, turkishContext.contains("[Oturum bağlamı]"))
    }

    @Test
    fun `a resumed session is not greeted again`() = runTest {
        // Resumption continues a conversation; greeting mid-sentence after a
        // dropped socket would be worse than the drop.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()
        val afterFirst = h.session.openingTurnsRequested
        assertEquals(1, afterFirst)

        h.session.emitEvent(LiveEvent.ResumptionHandle("handle-1"))
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(500)
        h.scope.testScheduler.runCurrent()

        // The resume itself must add nothing. Measured against the count
        // before it rather than against 1, so a retry on the *first* session
        // cannot be mistaken for a greeting on the resumed one.
        assertEquals("no second greeting", afterFirst, h.session.openingTurnsRequested)
    }

    @Test
    fun `a resumed session does not hold the microphone`() = runTest {
        // There is no greeting to protect on a reconnect, and the angler may
        // well be mid-sentence, so the mic must come straight back.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.ResumptionHandle("handle-1"))
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.testScheduler.advanceTimeBy(100)
        h.scope.testScheduler.runCurrent()

        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `speaking lasts until the speaker goes quiet, not until generation ends`() = runTest {
        // The service generates far faster than real time: measured at 16.5 s
        // of speech delivered in 5.0 s, with generationComplete at 5.04 s.
        // Leaving SPEAKING on that event froze the avatar's mouth two
        // syllables in and said "Listening" over the top of a talking
        // assistant.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()
        assertEquals(AssistantPhase.SPEAKING, h.controller.state.value.phase)

        h.session.emitEvent(LiveEvent.GenerationComplete)
        h.scope.testScheduler.advanceTimeBy(2_000)
        h.scope.testScheduler.runCurrent()
        assertEquals(
            "still audible, so still speaking",
            AssistantPhase.SPEAKING,
            h.controller.state.value.phase,
        )

        h.audible = false
        h.scope.advanceUntilIdle()
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `a barge-in ends the turn without waiting for the speaker`() = runTest {
        // flush() empties playback, so waiting for it to drain would hold
        // SPEAKING over an angler who has already cut in.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.GenerationComplete)
        h.scope.testScheduler.runCurrent()

        h.session.emitEvent(LiveEvent.Interrupted)
        h.scope.advanceUntilIdle()

        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `a server interruption stops playback and clears the queue`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.Interrupted)
        h.scope.advanceUntilIdle()

        verify { h.audioPlayer.flush() }
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `the gate opening stops playback without waiting for the server`() = runTest {
        // The server's own `interrupted` is a round trip away. Waiting for it
        // means the assistant talks over the angler's first words, which is
        // what makes barge-in feel broken even when it eventually works.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()
        assertEquals(AssistantPhase.SPEAKING, h.controller.state.value.phase)

        // What AudioCapture fires from the capture thread when the gate latches.
        h.audioCapture.onBargeIn()
        h.scope.advanceUntilIdle()

        verify { h.audioPlayer.flush() }
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `an interruption and a turn completion arriving together leave one state`() = runTest {
        // Both land within a frame of each other in practice. Whichever order
        // they arrive in, the session must end up listening and must not be
        // left SPEAKING by a drain watch that outlived the audio.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()

        h.session.emitEvent(LiveEvent.GenerationComplete)
        h.session.emitEvent(LiveEvent.Interrupted)
        h.audible = false
        h.scope.advanceUntilIdle()

        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
    }

    @Test
    fun `the capture reference is wired to the player so the gate can hear the leak`() = runTest {
        // Without this the gate has no far-end signal and falls back to an
        // absolute threshold, which is what made it either deaf or trigger
        // happy depending on the speaker volume.
        //
        // `echoReference`, not `outputLevel`: the gate needs what is in the
        // air, which keeps reporting through the gaps where playback is
        // starved, while the avatar needs what is scheduled and must go quiet
        // there. Wiring the gate to the mouth's measurement handed it a
        // reference of 0.00 against 7000 of leak.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        every { h.audioPlayer.echoReference } returns 0.42f
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        assertEquals(0.42f, h.audioCapture.speakerLevel(), 0.0001f)
    }

    @Test
    fun `hands-free is on by default and survives a reconnect`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        assertTrue(h.controller.state.value.handsFree)

        h.session.emitConnection(LiveConnectionState.Reconnecting(1, "test"))
        h.scope.advanceUntilIdle()
        h.session.emitConnection(LiveConnectionState.Connected)
        h.scope.advanceUntilIdle()

        assertTrue("a reconnect must not drop hands-free", h.controller.state.value.handsFree)
        // And the route must be re-asserted, or the resumed conversation
        // comes out of the earpiece.
        verify(atLeast = 2) { h.audioRouting.applyHandsFreeRouting() }
    }

    @Test
    fun `turning hands-free off hands routing back and is reflected in state`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.controller.setHandsFree(false)
        h.scope.advanceUntilIdle()

        assertFalse(h.controller.state.value.handsFree)
        verify { h.audioRouting.clearRouting() }
        // The microphone is not part of this decision.
        assertFalse(h.controller.state.value.isMuted)
    }

    @Test
    fun `the reported route follows the system, not a stored preference`() = runTest {
        // An earbud that runs flat mid-cast must stop the screen claiming
        // Bluetooth the moment audio moves to the loudspeaker.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()

        h.route.value = AudioRoute.BLUETOOTH
        h.scope.advanceUntilIdle()
        assertEquals(AudioRoute.BLUETOOTH, h.controller.state.value.audioRoute)

        h.route.value = AudioRoute.SPEAKER
        h.scope.advanceUntilIdle()
        assertEquals(AudioRoute.SPEAKER, h.controller.state.value.audioRoute)
        // Changing route must not silence anything.
        assertTrue(h.controller.state.value.handsFree)
    }

    @Test
    fun `ending fishing mode releases audio and leaves the microphone closed`() = runTest {
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        h.controller.stop()
        h.scope.advanceUntilIdle()

        verify { h.audioPlayer.stop() }
        verify { h.audioRouting.clearRouting() }
        verify { h.audioRouting.releaseFocus() }
        assertFalse(h.suppressed)
        assertEquals(AssistantPhase.IDLE, h.controller.state.value.phase)
    }

    @Test
    fun `an expired token renews once even though it is reported twice`() = runTest {
        // The service reports a dead token as both a LiveEvent.Error and a
        // LiveConnectionState.Failed, and both are observed. Acting on each
        // minted two tokens and opened two sockets: two live conversations
        // at once, both primed, neither aware of the other. On the device the
        // symptom was not audio — the session sat in "Listening", then in
        // "Thinking", and never returned.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        val connectsAfterStart = h.session.connectCount

        h.session.emitConnection(LiveConnectionState.Failed("token_expired", "expired"))
        h.session.emitEvent(LiveEvent.Error("token_expired", "expired", fatal = false))
        h.scope.advanceUntilIdle()

        assertEquals(
            "a dead token must open exactly one new socket",
            connectsAfterStart + 1,
            h.session.connectCount,
        )
    }

    @Test
    fun `a tool response with no answer behind it reconnects`() = runTest {
        // Seen on the device: two tool responses accepted, socket open, no
        // error, and nothing generated. The session sat in "Thinking" until
        // the angler quit fifteen seconds later.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.ResumptionHandle("handle-live"))
        h.session.emitToolCall("get_current_conditions", buildJsonObject { })
        // runCurrent, not advanceUntilIdle: the latter runs the watchdog's
        // own delay to completion, so the count would already include the
        // reconnect this test is trying to observe.
        h.scope.testScheduler.runCurrent()
        val connectsBefore = h.session.connectCount

        // Nothing comes back.
        h.scope.testScheduler.advanceTimeBy(11_000)
        h.scope.testScheduler.runCurrent()

        assertEquals("the lost turn must be picked up again", connectsBefore + 1, h.session.connectCount)
        assertEquals("handle-live", h.session.lastResumeHandle)
    }

    @Test
    fun `an answer stands the watchdog down`() = runTest {
        // The other half: a model that is simply slow must not be cut off.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.session.emitToolCall("get_current_conditions", buildJsonObject { })
        h.scope.testScheduler.runCurrent()
        val connectsBefore = h.session.connectCount

        h.session.emitAudio(ByteArray(960))
        h.scope.testScheduler.advanceTimeBy(11_000)
        h.scope.testScheduler.runCurrent()

        assertEquals("a reply must not trigger a reconnect", connectsBefore, h.session.connectCount)
    }

    @Test
    fun `tapping the assistant stops it without touching the socket`() = runTest {
        // The deterministic way out. Separating a voice from the
        // loudspeaker's own echo by level has almost no headroom at arm's
        // length, so this path exists precisely so that interrupting never
        // depends on a tuned number.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()
        h.audible = true
        h.session.emitAudio(ByteArray(960))
        h.scope.advanceUntilIdle()
        assertEquals(AssistantPhase.SPEAKING, h.controller.state.value.phase)
        val connects = h.session.connectCount

        h.controller.interrupt()
        h.scope.advanceUntilIdle()

        verify { h.audioPlayer.flush() }
        assertEquals(AssistantPhase.LISTENING, h.controller.state.value.phase)
        // The microphone keeps running and the socket is untouched: the
        // angler is interrupting, not restarting.
        assertEquals(connects, h.session.connectCount)
        assertFalse(h.controller.state.value.isMuted)
    }

    @Test
    fun `a look waits for more than one frame before saying it can see`() = runTest {
        // Reported from a shore: the camera blinked on and off and the
        // assistant did not seem to perceive anything. At the resting half a
        // frame per second, answering after one frame means answering about
        // whatever the phone was pointing at in the instant the model decided
        // to look — before the angler has raised it.
        val camera = FakeCameraSource()
        val h = Harness(camera = camera, credentialsResult = { AppResult.Ok(credentials()) })
        h.controller.start("tr", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        h.session.emitToolCall("set_camera", buildJsonObject { put("enabled", true) })
        // Time is driven by hand: `advanceUntilIdle` would run the camera to
        // the end of its frames whatever the rule, and an assertion made
        // after that passes against a one-frame look too. The question is
        // whether the *answer* waits, so the clock has to stop where the
        // first frame has arrived and the rest have not.
        h.scope.testScheduler.runCurrent()

        assertTrue(
            "the model was told it could see after a single frame",
            h.session.sentToolResults.isEmpty(),
        )

        h.scope.advanceUntilIdle()
        assertTrue("the look never completed", h.session.sentToolResults.isNotEmpty())
        assertTrue(
            "a look must gather more than one frame, got ${h.controller.state.value.framesSent}",
            h.controller.state.value.framesSent > 1,
        )
    }

    @Test
    fun `an inland angler is not asked to show the water`() = runTest {
        // The brief says whether there is a sea state where they are standing.
        // Without it the opening tells someone sitting at home twenty
        // kilometres inland to point a camera at the sea.
        val h = Harness(credentialsResult = { AppResult.Ok(credentials()) })
        every { h.credentialsRepo.lastUserBrief } returns buildJsonObject {
            put("display_name", "Deniz")
            put("sea_state_at_position", false)
        }
        h.controller.start("en", false)
        h.scope.advanceUntilIdle()
        h.session.emitEvent(LiveEvent.Ready)
        h.scope.advanceUntilIdle()

        val primed = h.session.primedContext.flatten().joinToString("\n") { it.text }
        assertTrue("states the fact: $primed", primed.contains("no sea state at their position"))
        assertTrue("asks where they will fish: $primed", primed.contains("where they are"))
        assertFalse("does not ask to see the water: $primed", primed.contains("ask to see the water"))
    }
}
