# Gemini Live API integration

> **Superseded in part.** A full compliance audit against the current official
> documentation was run after this document was written, and added several
> settings (`mediaResolution`, `proactivity`, `enableAffectiveDialog`,
> `activityHandling`, `turnCoverage`), replaced the hand-rolled wire conversion
> with the SDK's own, and surfaced server events that were being dropped.
>
> **[GEMINI_LIVE_IMPLEMENTATION.md](GEMINI_LIVE_IMPLEMENTATION.md)** is the
> authoritative reference for the current configuration, with a citation for
> each setting. This document remains useful for the narrative background.

Everything in this document was verified against the current official
documentation and the installed SDK at implementation time, not recalled from
older examples. Where a value is load-bearing, the reason is stated.

---

## 1. Model

| Setting | Value | Why |
|---|---|---|
| Live model | `gemini-3.8-live` | Current **stable** Live API model; lowest latency for a voice agent without reasoning delay |
| Alternative | `gemini-3.8-live-extended-thinking` | Higher background reasoning, more latency. Set `GEMINI_LIVE_MODEL` to switch. |
| Text model | `gemini-3.8-flash` | Server-side grounded regulation lookups |

Configured in one place (`GEMINI_LIVE_MODEL`), so a model bump is an env change.

---

## 2. Transport

```
wss://generativelanguage.googleapis.com/ws/
  google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained
  ?access_token=<ephemeral token>
```

Two things are easy to get wrong here:

* The **`Constrained`** method is the one ephemeral tokens use. The plain
  `BidiGenerateContent` method expects an API key.
* The token goes in `access_token`, not `key`.

Only `v1beta` supports ephemeral tokens with the Live API.

### Why a raw WebSocket on Android

The Google Gen AI SDK for Kotlin exists and supports `client.live.connect(...)`,
but it is **explicitly unsupported on Android** — Google blocks mobile use and
points at Firebase AI Logic instead. Connecting directly with a constrained
ephemeral token is the documented client-to-server pattern, so FishMate uses
OkHttp's WebSocket behind the `LiveAiSession` interface. This also gives us
direct control over reconnection, which matters more here than SDK convenience.

---

## 3. Audio

| Direction | Format |
|---|---|
| Input | raw 16-bit PCM, **16 kHz**, mono, little-endian, base64 |
| Output | raw 16-bit PCM, **24 kHz**, mono, little-endian, base64 |

The rates differ. Playing the model's 24 kHz output through a 16 kHz track
produces a slow, deep voice — a confusing bug to chase, so the two rates are
separate fields on `LiveCredentials` and come from the server.

`ENCODING_PCM_16BIT` on Android is already little-endian and `AudioRecord` is
asked for 16 kHz directly, so **no resampling is needed** — which matters,
because resampling on the audio thread adds latency to every utterance.

### The most important audio decision

`AudioCapture` uses `MediaRecorder.AudioSource.VOICE_COMMUNICATION`, not `MIC`.
That enables the platform's echo canceller. Without it, the model hears itself
through the phone speaker, server-side VAD treats that as the user speaking,
and the assistant interrupts itself continuously. `AudioTrack` pairs this with
`USAGE_VOICE_COMMUNICATION`.

Chunks are **20 ms** (640 bytes). Live API guidance is explicitly to avoid
large buffers; small chunks keep perceived latency low.

### Barge-in

`serverContent.interrupted` means the angler talked over the model. Handling it
needs *both* halves:

```kotlin
queue.clear()        // stop future writes
track.pause(); track.flush(); track.play()   // drop what the hardware holds
```

Clearing only our queue leaves a second or more of already-buffered speech
playing after the user cut in, which feels broken.

---

## 4. Video

Realtime video is sent as **periodic JPEG stills**, not a continuous stream:

```json
{"realtimeInput": {"video": {"data": "<base64 jpeg>", "mimeType": "image/jpeg"}}}
```

Defaults (`FrameConfig`): **0.5 fps, 768 px longest edge, JPEG quality 70**.
That is enough for the model to identify a lure or a fish, and it keeps a
three-hour session affordable on a phone battery and a mobile data plan.
Throttling happens *before* YUV→JPEG conversion, so frames we are about to
discard cost no CPU.

Frames are rotation-corrected using `imageInfo.rotationDegrees`. A sideways
image measurably degrades identification quality.

---

## 5. Session management

This is the part that decides whether a two-hour shore session works.

| Documented limit | Value |
|---|---|
| Audio-only session | **15 minutes** |
| Audio **+ video** session | **2 minutes** |
| Single connection lifetime | **~10 minutes** |
| Resumption handle validity | 2 hours after termination |

A two-minute ceiling is shorter than a single conversation about a lure, so
both mitigations are always on:

### Context window compression

```json
"contextWindowCompression": {
  "triggerTokens": 16000,
  "slidingWindow": { "targetTokens": 8000 }
}
```

Slides the context window instead of letting the server end the session.

### Session resumption

`"sessionResumption": {}` in setup enables it. The server then pushes:

```json
{"sessionResumptionUpdate": {"newHandle": "...", "resumable": true}}
```

The client stores the latest handle and, on reconnect, sends
`"sessionResumption": {"handle": "..."}`. **This is the single most important
piece of client state**: with it, a dropped connection continues the same
conversation; without it, the assistant forgets everything mid-sentence and the
angler notices immediately.

### `goAway` is routine, not an error

```json
{"goAway": {"timeLeft": "9.5s"}}
```

The server is rotating the connection. FishMate reconnects **proactively**,
leaving a 1.5 s safety margin, so the new socket is up before the old one dies
and the user hears no gap. The UI shows "Reconnecting…", never "Something went
wrong".

### Credential renewal

Tokens live 30 minutes; sessions run longer. On `token_expired` the controller
re-mints and reconnects with the stored handle. Reconnecting with the same
token is permitted even at `uses: 1`, because resumption is not a new session.

---

## 6. Voice activity detection

```json
"realtimeInputConfig": {
  "automaticActivityDetection": {
    "disabled": false,
    "prefixPaddingMs": 300,
    "silenceDurationMs": 900
  }
}
```

Server-side VAD is what makes the product hands-free: no button per sentence,
and barge-in works. `silenceDurationMs` is raised to 900 ms deliberately — the
default cuts people off mid-sentence, and a windy shoreline with an angler
thinking aloud needs more patience than a quiet office.

---

## 7. Function calling

The loop the brief specifies, implemented literally:

```
Gemini Live
   │  {"toolCall": {"functionCalls": [{"id","name","args"}]}}
   ▼
Android ToolOrchestrator
   │  POST /api/v1/tools/execute-batch   (authenticated)
   ▼
Backend FishingToolRegistry
   │  validate (Pydantic) → execute → audit
   ▼
services / PostgreSQL / Open-Meteo
   │
   ▼  {"toolResponse": {"functionResponses": [{"id","name","response"}]}}
Gemini Live → spoken answer
```

Twelve tools: `get_current_conditions`, `get_conditions_forecast`,
`get_sun_times`, `get_tackle_inventory`, `get_recent_catches`,
`get_recent_sessions`, `recommend_setup`, `start_fishing_session`,
`finish_fishing_session`, `log_catch`, `add_session_observation`,
`get_fishing_regulations` — plus the built-in `google_search` for general
questions we have no local tool for.

### Non-obvious details that matter

* **Batching.** One `toolCall` can contain several `functionCalls`, and the
  server expects **one** `toolResponse` containing all of them. Hence
  `/tools/execute-batch`.
* **Every call gets an answer.** If the network is down, the orchestrator
  synthesises an error response per call. An unanswered `toolCall` hangs the
  conversation: the assistant simply goes quiet and the angler assumes the app
  crashed. Tested in `ToolOrchestratorTest`.
* **Argument names stay snake_case.** The wire protocol is camelCase, but tool
  `parameters.properties` keys are *our* argument names, matched against
  `app/tools/schemas.py`. `to_wire_config()` converts protocol keys and leaves
  declarations verbatim; `test_tool_argument_names_are_not_mangled` guards it.
* **No model-generated code or SQL is executed.** Tools are a fixed,
  hand-written set; the model only supplies arguments, which are validated with
  `extra="forbid"` before anything runs.
* **`instruction_to_model` in the response.** Tool results carry a short
  directive — "NOT VERIFIED, do not state this as fact", "there is no recorded
  catch, say so plainly". It restates the system instruction at call time, which
  is the last line of defence against a confident wrong answer.

---

## 8. Transcription

```json
"inputAudioTranscription": {}, "outputAudioTranscription": {}
```

Both on. They drive the optional on-screen transcript and let us persist a
conversation summary **without recording audio**.

Transcripts arrive as fragments ("Bu", " küçük", " bir soft plastic"), so
`List<TranscriptLine>.append` merges consecutive fragments from the same
speaker — otherwise the transcript is one word per line. Capped at 200 lines so
a long session cannot grow without bound.

---

## 9. Cost control

Live sessions bill on streamed media, so cost is an engineering concern:

| Lever | Implementation |
|---|---|
| Explicit Fishing Mode | Nothing streams until the angler starts it; stopping tears down the socket, mic and camera |
| Camera off by default | Voice-only until the angler opts into video |
| Frame throttling | 0.5 fps / 768 px / q70 by default, adjustable in Settings |
| One-shot capture | "Look at this" sends a single frame instead of starting a stream |
| Context compression | Caps context growth on long sessions |
| Combined conditions tool | One call returns weather + wind + sea state, instead of three |
| Weather caching | 10-minute TTL per coordinate |
| Tool restraint | System instruction tells the model not to call tools gratuitously |
| Usage tracking | `usageMetadata` recorded in `FishingModeState` |

Dominant drivers, in order: **audio minutes → video frames → tool calls**. The
cheapest meaningful optimisation is keeping the camera off, which is why it is
off by default.

---

## 10. Connection state machine

```
IDLE ──start──▶ CONNECTING ──setupComplete──▶ LISTENING ⇄ SPEAKING
                     │                            │  ▲
                     │                     interrupted │
                     │                            ▼  │
                     │                        TOOL_CALL → THINKING
                     │
                     ├── goAway / drop ──▶ RECONNECTING ──┐
                     │                         │ resumption handle
                     │                         └──────────┘
                     └── token_expired ──▶ re-mint ──▶ RECONNECTING
                                   failure ──▶ ERROR (user-facing message)
```

Handled explicitly: WebSocket failure, 401/403 (token dead — re-mint rather than
retry), `goAway`, unexpected close, network change, microphone loss, audio-focus
loss, camera failure, app lifecycle, and token expiry. Every one of these has a
user-facing state; none of them is a crash.
