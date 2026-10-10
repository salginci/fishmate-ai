# GDE feature matrix — Gemini Live API

Nothing is marked implemented without a file and a line to inspect. Where a
capability is *not* implemented, the reason is stated rather than hidden.

Paths: `A/` = `android/app/src/main/java/com/fishmate/ai/`,
`B/` = `backend/app/`. Line numbers are from the audit commit.

---

## Core Live API capabilities

| Gemini Live capability | Implemented | File | Evidence |
|---|---|---|---|
| **Bidirectional audio** | ✅ | `A/live/GeminiLiveSession.kt:160` (send) · `:360` (receive) | `realtimeInput.audio` out, `serverContent.modelTurn.parts[].inlineData` in, over one persistent WebSocket |
| **Native audio** | ✅ | `A/audio/AudioCapture.kt:24` · `A/audio/AudioPlayer.kt:27` | Mic PCM 16 kHz → Live API → 24 kHz PCM → `AudioTrack`. No STT/TTS anywhere in the repo |
| **Video input (realtime)** | ✅ | `A/camera/PhoneCameraSource.kt:57` · `A/live/GeminiLiveSession.kt:177` | CameraX `ImageAnalysis` → throttled JPEG → `realtimeInput.video` |
| **Image input (one-shot)** | ✅ | `A/live/FishingModeController.kt` `captureAndSendFrame()` | "Look at this" sends a single frame instead of starting a stream |
| **Media resolution control** | ✅ | `B/services/live_tokens.py:110` | `mediaResolution: MEDIA_RESOLUTION_LOW` caps vision tokens per frame |
| **Function calling** | ✅ | `A/live/GeminiLiveSession.kt:374` (recv) · `:216` (resp) · `B/tools/registry.py:586` | Real `toolCall`/`toolResponse`; 12 typed tools; ids echoed; batched |
| **Async function calling** | ⚙ implemented, **off by default** | `B/tools/declarations.py:368` · `A/live/protocol/Wire.kt` (`scheduling`) | `behavior: NON_BLOCKING` for `SLOW_TOOLS`; off because the docs do not state support for `gemini-3.8-live` — see note below |
| **Google Search grounding** | ✅ | `B/tools/declarations.py:374` · `A/live/GeminiLiveSession.kt:337` | Built-in `{"googleSearch":{}}` tool; `groundingMetadata` parsed into `LiveEvent.Grounding` |
| **Session resumption** | ✅ | `A/live/GeminiLiveSession.kt:389` · `:136` | Stores `sessionResumptionUpdate.newHandle`; replays it in the `setup` frame |
| **Context compression** | ✅ | `B/services/live_tokens.py:110` | `contextWindowCompression.slidingWindow.targetTokens` 8 000, `triggerTokens` 16 000 |
| **Ephemeral tokens** | ✅ | `B/services/live_tokens.py:272` · `:279` | `auth_tokens.create` with `live_connect_constraints` + `lock_additional_fields`; APK holds no Gemini key |
| **Transcription (input)** | ✅ | `B/.../live_tokens.py` (`inputAudioTranscription`) · `A/live/GeminiLiveSession.kt` | Finalised user transcript |
| **Transcription (interim)** | ✅ | `A/live/protocol/Wire.kt` · `A/live/FishingModeController.kt` | `interimInputTranscription` → `FishingModeState.interimTranscript` |
| **Transcription (output)** | ✅ | same | Model speech transcript, drives the on-screen transcript |
| **Interruption (barge-in)** | ✅ | `A/live/FishingModeController.kt:323` · `A/audio/AudioPlayer.kt:131` | `interrupted` → clear queue **and** `AudioTrack.flush()` |
| **Reconnection** | ✅ | `A/live/GeminiLiveSession.kt:397` (`goAway`) · `:452` (backoff) | Proactive reconnect 1.5 s before the deadline; exponential backoff with jitter |

---

## Session configuration

| Setting | Value | File |
|---|---|---|
| Model | `gemini-3.8-live` | `B/config.py:27` |
| `responseModalities` | `["AUDIO"]` | `B/services/live_tokens.py:110` |
| `speechConfig.…voiceName` | `Kore` (configurable) | same |
| `languageCode` | **not sent** — native audio chooses | same |
| `thinkingConfig` | **not sent** — unsupported on 3.8 Live, and locked | same + `:69` |
| `inputAudioTranscription` | `{}` | same |
| `outputAudioTranscription` | `{}` | same |
| `sessionResumption` | `{}` | same |
| `contextWindowCompression` | sliding window | same |
| `mediaResolution` | `MEDIA_RESOLUTION_LOW` | same |
| `proactivity` | **not sent** — live service rejects it (1007) | same |
| `enableAffectiveDialog` | **not sent** — fatal on first generation (1007) | same |
| `automaticActivityDetection` | enabled, 300 ms padding, 900 ms silence | same |
| `activityHandling` | `START_OF_ACTIVITY_INTERRUPTS` | same |
| `turnCoverage` | `TURN_INCLUDES_ONLY_ACTIVITY` | same |
| API version | `v1beta` | `B/config.py` |

### Why proactive audio and affective dialog are absent

Both are documented as supported on Gemini 3.8 Live, and both were implemented
during this audit. The live service then rejected them:

* `proactivity` → `1007 Unknown name "proactivity" at 'setup'` — no session
  starts at all.
* `enable_affective_dialog` → accepted at setup, then
  `1007 Request contains an invalid argument` on the first generation turn.

Verified 2026-10-07. Both were removed rather than left as toggles, because a
toggle that breaks every session is not a feature. This is the clearest example
of why `make verify-live` exists alongside the unit tests.

---

## Server event coverage

Every field the API reference lists on `serverContent` / the server message is
either handled or deliberately tolerated. A dropped field is invisible at
runtime, which is why this is enumerated.

| Field | Handled | Where |
|---|---|---|
| `setupComplete` | ✅ | `→ LiveEvent.Ready`, starts media |
| `modelTurn.parts[]` | ✅ | **iterated** — multiple parts per event |
| `inlineData` (audio) | ✅ | `→ LiveEvent.AudioChunk` |
| `part.text` | ✅ | `→ LiveEvent.OutputTranscript` |
| `interrupted` | ✅ | `→ LiveEvent.Interrupted`, flushes playback |
| `turnComplete` | ✅ | `→ LiveEvent.TurnComplete` |
| `generationComplete` | ✅ | `→ LiveEvent.GenerationComplete` |
| `inputTranscription` | ✅ | `→ LiveEvent.InputTranscript` |
| `interimInputTranscription` | ✅ | `→ LiveEvent.InterimInputTranscript` |
| `outputTranscription` | ✅ | `→ LiveEvent.OutputTranscript` |
| `groundingMetadata` | ✅ | `→ LiveEvent.Grounding` (queries + sources) |
| `waitingForInput` | ✅ | `→ LiveEvent.WaitingForInput` |
| `urlContextMetadata` | ◻ modelled, unused | URL context is not supported in Live API |
| `interactionStatus` | ◻ modelled, unused | no product use yet; retained so it is not silently dropped |
| `toolCall` | ✅ | `→ LiveEvent.ToolCalls` |
| `toolCallCancellation` | ✅ | `→ LiveEvent.ToolCallsCancelled` |
| `goAway` | ✅ | `→ LiveEvent.GoingAway`, proactive reconnect |
| `sessionResumptionUpdate` | ✅ | handle stored |
| `usageMetadata` | ✅ | `→ LiveEvent.Usage`, surfaced for cost |
| unknown fields | ✅ tolerated | `ignoreUnknownKeys` — a new field must not kill a session |

**One event ≠ one part.** `modelTurn.parts` is iterated
(`A/live/GeminiLiveSession.kt:~355`) and the top-level fields are checked
independently, never as `else if`. Asserted by
`ServerEventCoverageTest::one event can carry several independent signals`.

---

## Client → server messages

| Message | Used for | File |
|---|---|---|
| `setup` | first frame, echoed from the backend | `A/live/GeminiLiveSession.kt:136` |
| `realtimeInput.audio` | microphone, 20 ms chunks | `:160` |
| `realtimeInput.video` | throttled JPEG frames | `:177` |
| `realtimeInput.text` | typed input / demo | `:~190` |
| `realtimeInput.audioStreamEnd` | mute, end of turn | `:~230` |
| `clientContent` (`turnComplete: false`) | priming fishing history | `:200` |
| `toolResponse.functionResponses` | tool results (+ `scheduling`) | `:216` |

`clientContent` with `turnComplete: true` is **never** sent — the docs state it
unconditionally interrupts generation, and we are adding context, not asking a
question.

---

## Not implemented, and why

| Capability | Why not |
|---|---|
| **Code execution** | Not supported in the Live API |
| **URL context** | Not supported in the Live API |
| `thinkingLevel` | Not supported on `gemini-3.8-live`; the docs say to omit it. Omitted *and* locked against the client |
| Async function calling **enabled** | Docs state only that Gemini 3.1 Flash Live lacks it and do **not** confirm support for 3.8 Live. Implemented behind a flag; would also gain little, since the slowest tool measured 318 ms |
| Extended-thinking model | Latency beats depth for one-sentence answers while holding a rod; one env change if that flips |
| Gen AI SDK on Android | Google blocks Android for that SDK and recommends connecting directly with an ephemeral token |

---

## Test and verification evidence

| What | Count | Contacts Google? |
|---|---|---|
| Backend tests | **246** | No — `FakeLiveTokenService`, mock weather |
| Android unit tests | **63** | No — `MockLiveAiSession` |
| Live API verification script | 10 checks | **Yes** — `make verify-live` |

Tests that specifically pin Live API compliance:

| Test | Pins |
|---|---|
| `TestGemini38LiveCompliance::test_thinking_config_is_never_sent` | `thinkingLevel` absent from config *and* wire |
| `…::test_thinking_config_is_locked_against_the_client` | present in `LOCKED_ADDITIONAL_FIELDS` |
| `…::test_language_code_is_not_set` | native audio chooses language |
| `…::test_proactive_audio_enabled` / `_can_be_disabled` | `proactivity.proactiveAudio` |
| `…::test_affective_dialog_enabled` / `_can_be_disabled` | `enableAffectiveDialog` |
| `…::test_media_resolution_bounded_for_cost` | `MEDIA_RESOLUTION_LOW` |
| `…::test_barge_in_and_turn_coverage_are_explicit` | `activityHandling`, `turnCoverage` |
| `…::test_audio_only_response_modality` | `["AUDIO"]` |
| `TestWireFormatIsSdkDerived::test_matches_the_sdk_dump_exactly` | wire JSON == SDK's own `by_alias` dump |
| `…::test_no_snake_case_protocol_keys_survive` | no mis-cased protocol key |
| `…::test_tool_argument_names_are_not_mangled` | `ai_confidence`, `length_cm` preserved |
| `…::test_google_search_is_the_builtin_tool` | `{"googleSearch":{}}` present |
| `TestAsyncFunctionCalling::test_disabled_by_default` | no `behavior` field by default |
| `…::test_enabled_only_for_slow_tools` | `NON_BLOCKING` only on `SLOW_TOOLS` |
| `test_every_declared_arg_matches_a_pydantic_field` | declaration ↔ validator drift guard |
| `TestMinting::test_mint_locks_model_config_and_extra_fields` | constraints actually sent |
| `ServerEventCoverageTest` (×7) | interim transcript, grounding, `waitingForInput`, multi-part events, `clientContent`, `scheduling` |
| `ClientContentRoleTest` | explicit `user`/`model` roles reach the wire |
| `FishingModeControllerTest` (×16) | barge-in, tool loop, resumption, token renewal |

```bash
make check          # backend: lint + format + mypy + 246 tests
make test-android   # 63 tests
make verify-live    # real Live API, needs GEMINI_API_KEY
```
