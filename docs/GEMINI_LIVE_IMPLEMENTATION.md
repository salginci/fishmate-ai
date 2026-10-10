# Gemini Live API — implementation reference

Audited against the current official documentation **and against the live
service**. Where the two disagree, the live service wins and the discrepancy is
recorded — there are two such cases, both of which would have shipped broken.

Verified end to end on 2026-10-07 against `gemini-3.8-live` with a real key:
connect → audio in → video in → tool call → tool response → **394 KB of native
Turkish audio** → session resumption. Reproduce with `make verify-live`.

Sources used, all `ai.google.dev`:
[live-api](https://ai.google.dev/gemini-api/docs/live-api) ·
[capabilities](https://ai.google.dev/gemini-api/docs/live-api/capabilities) ·
[tools](https://ai.google.dev/gemini-api/docs/live-api/tools) ·
[session-management](https://ai.google.dev/gemini-api/docs/live-api/session-management) ·
[ephemeral-tokens](https://ai.google.dev/gemini-api/docs/live-api/ephemeral-tokens) ·
[get-started-sdk](https://ai.google.dev/gemini-api/docs/live-api/get-started-sdk) ·
[API reference](https://ai.google.dev/api/live) ·
[changelog](https://ai.google.dev/gemini-api/docs/changelog)

---

## Model

| | |
|---|---|
| **Model ID** | `gemini-3.8-live` |
| Configured at | `backend/app/config.py:27` (`GEMINI_LIVE_MODEL`) |
| Status | GA since **15 September 2026** (changelog) |
| Alternative | `gemini-3.8-live-extended-thinking` — one env change |

No legacy Live model appears anywhere in the repository. `gemini-2.0-flash-live-001`
and `gemini-live-2.5-flash-preview` were shut down on 9 December 2025 and are
not referenced; `gemini-3.1-flash-live-preview` is not used.

### Why not Extended Thinking

Extended Thinking adds background reasoning and the configurable
`thinking_level`. This product answers while someone is holding a rod — the
system instruction explicitly demands one- or two-sentence replies — so
latency beats depth. The decision is a single environment variable if that
trade ever changes.

### `thinkingLevel` is deliberately absent

The capabilities documentation states that for Gemini 3.8 Live thinking is
*"Supported (interleaved reasoning). `thinkingLevel` is not supported (omit
from setup)."*

So `build_live_config` sends no `thinking_config` / `thinkingLevel`, and the
field is additionally named in `LOCKED_ADDITIONAL_FIELDS`
(`backend/app/services/live_tokens.py:69`) so a tampered client cannot add it.
Asserted by `TestGemini38LiveCompliance::test_thinking_config_is_never_sent`.

---

## SDK

| Side | Package | Version | Role |
|---|---|---|---|
| Backend | `google-genai` (official) | **2.28.0** | mints ephemeral tokens; produces the wire config; grounded regulation lookups |
| Android | OkHttp `5.5.0` WebSocket | — | the Live session itself |

### Why Android does not use a Gemini SDK

The Google Gen AI SDK for Kotlin supports `client.live.connect(...)`, but
**Android is explicitly not a supported target** — Google blocks mobile use and
directs mobile clients to Firebase AI Logic or to connecting directly. The
Live API overview recommends client-to-server for media ("Client-to-server
generally offers better performance for streaming audio and video, since it
bypasses the need to send the stream to your backend first") and recommends
ephemeral tokens for it in production.

So the Android client speaks the documented `BidiGenerateContent` protocol over
a raw WebSocket, behind the `LiveAiSession` interface. The *protocol framing*
is hand-written; the *configuration* is not — see below.

### The wire format comes from the SDK, not from us

`to_wire_config()` (`backend/app/services/live_tokens.py:189`) does **not**
convert key names itself. It validates our config into
`types.LiveConnectConfig` and dumps it `by_alias`, so the camelCase spelling
(`responseModalities`, `proactiveAudio`, `prefixPaddingMs`, …) is Google's.

This matters because **the Live API silently ignores fields it does not
recognise**. A hand-rolled converter that mis-cases one key produces an
assistant that is quietly missing a capability, with no error anywhere.
`TestWireFormatIsSdkDerived::test_matches_the_sdk_dump_exactly` pins it.

The one thing that must *not* be converted is a tool's
`parameters.properties` keys — those are our own argument names
(`ai_confidence`, `length_cm`) matched against `app/tools/schemas.py` on the
way back. The SDK preserves them verbatim; a test asserts it.

---

## Findings that contradict the documentation

Three things only a real connection revealed. Each is pinned by a test.

### 1. The wire `setup` has no `config` wrapper

`BidiGenerateContentSetup` carries `model`, `generationConfig`,
`systemInstruction`, `tools`, `realtimeInputConfig`, `sessionResumption`,
`contextWindowCompression` and the transcription settings as **siblings**. The
`config` nesting belongs to the SDK's object model. Sending it:

```
1007  Unknown name "config" at 'setup': Cannot find field.
```

Also: `model` requires the `models/` prefix, and `responseModalities`,
`speechConfig` and `mediaResolution` live under `generationConfig`.

`build_wire_setup()` therefore delegates to the SDK's own
`_LiveConnectParameters_to_mldev` converter — the same code path
`client.aio.live.connect()` uses — rather than hand-building the mapping.

### 2. `proactivity` is rejected

The capabilities page lists proactive audio as supported on Gemini 3.8 Live.
The live v1beta service disagrees:

```
1007  Unknown name "proactivity" at 'setup': Cannot find field.
```

Fatal — no session starts. Removed, and not offered as a toggle.

### 3. `enable_affective_dialog` is accepted, then fatal

Also listed as supported. The service **accepts it at setup** and then kills
the first generation turn:

```
1007  Request contains an invalid argument.
```

The worst failure mode: it looks configured and breaks on the first reply.
Removed.

### Also observed

* **`googleSearch` needs its own quota.** A key without it fails the *whole
  session* at setup with `1011 ... exceeded your current quota` (3/3 attempts).
  `GEMINI_ENABLE_GOOGLE_SEARCH` therefore defaults to **false**: fail-safe beats
  fail-fatal for a capability the product can live without.
* **`session.receive()` is a per-turn generator.** It completes at
  `turnComplete`. Since the model ends its turn when it issues a tool call, the
  spoken answer needs a *fresh* `receive()`. Holding one iterator across turns
  silently reports zero audio.
* **1011 "Internal error encountered" is flaky**, roughly 50% of runs on the
  turn after a tool response, independent of inputs. Server-side; production
  handles it by reconnecting with the resumption handle.
* The SDK's converter emits **top-level setup keys in camelCase but nested keys
  in snake_case** (`realtimeInputConfig.activity_handling`). Proto3 JSON accepts
  both; this is byte-for-byte what the SDK sends.

---

## Connection

```
Android                         FishMate backend                Gemini
   │ POST /api/v1/live/session        │                             │
   │────────────────────────────────▶ │ auth_tokens.create(         │
   │                                  │   live_connect_constraints) │
   │                                  │────────────────────────────▶│
   │                                  │◀──── ephemeral token ───────│
   │◀─ token + wire-ready `setup` ─── │                             │
   │                                                                │
   │ wss://…BidiGenerateContentConstrained?access_token=…           │
   │───────────────────────────────────────────────────────────────▶│
   │ {"setup": {...}}  ── first frame, echoed verbatim ────────────▶│
   │◀────────────────────────────────────── {"setupComplete": {}} ──│
```

| Step | Code |
|---|---|
| Mint token | `backend/app/services/live_tokens.py:272` |
| Endpoint path | `backend/app/services/live_tokens.py:53` — `BidiGenerateContentConstrained` |
| Open socket | `android/.../live/GeminiLiveSession.kt:126` |
| Build `setup` frame | `android/.../live/GeminiLiveSession.kt:136` |

Two details that are easy to get wrong: ephemeral tokens must use the
**`Constrained`** bidi method (the plain one expects an API key), and the token
goes in **`access_token`**, not `key`. Only `v1beta` supports ephemeral tokens
with the Live API, which is why the SDK client is pinned to that API version.

The client does not invent its own setup message. It echoes the server-built
`setup`, injecting only the session-resumption handle — client-side state the
server cannot know.

---

## Authentication

The real `GEMINI_API_KEY` never leaves the backend; the APK contains no Gemini
credential. Full reasoning in [SECURITY.md](SECURITY.md); the load-bearing part:

```python
live_connect_constraints=types.LiveConnectConstraints(
    model=model_name,     # pinned
    config=typed_config,  # pinned: modality, voice, tools, system instruction,
),                        #         session management, proactivity, …
lock_additional_fields=[
    "temperature", "top_p", "top_k", "max_output_tokens", "seed",
    "thinking_config",    # unsupported on 3.8 Live
],
```

An ephemeral token minted *without* constraints is nearly as dangerous as a
leaked key: the holder can use any model, any system instruction and any tool
set on our quota. Because the config is locked, the safety rules in the system
instruction hold **even against a modified APK**, since the client never
supplies one.

Lifetime: 1 minute to open a session, 30 minutes to use it, `uses: 1`.
Reconnecting via session resumption inside that window is permitted even at
`uses: 1` — which is what makes a long session work.

---

## Audio

| Direction | Format | Code |
|---|---|---|
| Input | raw 16-bit PCM, **16 kHz**, mono, LE, `audio/pcm;rate=16000` | `audio/AudioCapture.kt` |
| Output | raw 16-bit PCM, **24 kHz**, mono, LE | `audio/AudioPlayer.kt:27` |

The rates differ. Playing 24 kHz output through a 16 kHz track yields a slow,
deep voice, so both come from the server in `LiveSessionResponse`.

`ENCODING_PCM_16BIT` on Android is already little-endian and `AudioRecord` is
asked for 16 kHz directly, so **no resampling** happens on the audio thread.
Chunks are **20 ms** (640 bytes) — the documentation's guidance is explicitly
to avoid large buffers.

**The single most important audio decision**: `AudioCapture` uses
`MediaRecorder.AudioSource.VOICE_COMMUNICATION` (`audio/AudioCapture.kt:24`),
not `MIC`, which engages the platform echo canceller. With `MIC`, the model
hears itself through the speaker, server-side VAD reads that as the user
speaking, and the assistant interrupts itself continuously.

That source alone is **not sufficient on a loudspeaker** — see below.

### The echo loop, and why it is the hardest part of hands-free

Measured on a Galaxy A35 with the phone on loudspeaker: **32 interruptions in
50 seconds**, one roughly every three seconds, with the conversation never
completing a sentence. The mechanism is a closed loop:

1. The model speaks; the audio leaves the loudspeaker.
2. The microphone picks it up.
3. Those bytes go to the Live API as realtime input.
4. Server-side VAD sees input speech and emits `serverContent.interrupted`.
5. Playback is flushed, the model restarts — and hears itself again.

The same echo explains a second symptom that looks unrelated: the model
**switching to languages nobody spoke** (Spanish, in our case). It is
language-detecting a distorted copy of its own output. Suspect echo before
suspecting the prompt.

Three layers fix it, in this order:

1. **Attach the effects explicitly.** `VOICE_COMMUNICATION` is documented to
   engage them, but on many devices they are not attached unless asked for:
   `AcousticEchoCanceler.create(sessionId)` and `NoiseSuppressor.create(...)`
   against `AudioRecord.audioSessionId` (`audio/AudioCapture.kt`). Confirm in
   logcat — `AudioCapture: effects: aec=true ns=true` — rather than assuming.
2. **Gate the microphone against the echo while the model speaks**
   (`audio/EchoGate.kt`), but only when the output really is the built-in
   speaker. On a headset there is no acoustic path, and gating would only cost
   responsiveness.
3. **Do not mute outright.** Muting for the duration of each turn removes
   barge-in, which is the point of a hands-free assistant.

The gate compares each 20 ms frame against a running estimate of the echo.
Two things about that estimate were both wrong on the first attempt, and both
produced plausible-looking code:

- Adapting the floor on **every** frame lets it chase the angler's own voice
  upward, and barge-in stops working after two or three frames. The floor must
  only learn from frames that already look like echo.
- Seeding the floor from the **first** frames of a turn measures the quiet
  lead-in before the first syllable. On the device the floor settled at an RMS
  of about 5, so the speech echo that followed at about 3000 looked like a
  barge-in — logged as `barge-in accepted (rms=3369 floor=13)` seven times in a
  minute with nobody in the room. There is a 300 ms warm-up that measures the
  loudest part of the lead-in and admits nothing while it does, and the floor
  then rises instantly but decays slowly, so it tracks the loud part of speech
  rather than the gaps between words.

After all three layers: **0 interruptions in 90 seconds** on the same device
and the same room.

This is **native audio end to end**. There is no STT→text→TTS path anywhere in
the repository: microphone bytes go to the Live API, and `inlineData` audio
comes back and is played. The only transcription is the API's own, used for the
on-screen transcript.

#### Measuring whether the loop is back

The loop's signature in the transcript is the same sentence appearing twice:
the model is cut off, restarts, and because restarts merge into the line being
spoken the duplication shows up *inside* one line rather than across lines.
`RepeatCounter` (both clients) keeps a cumulative per-session count of that,
so "is it repeating itself?" is answered by a number read off a real session
rather than by listening and forming an impression. Short fragments are
ignored — "yes", "tamam", "mm" recur legitimately and would swamp the count.

It is incremental rather than a rescan of the transcript, because both clients
run the Live event loop on the UI thread next to a 60 fps avatar, and
transcript fragments arrive several times a second. Only the line still being
spoken can change, so only that one is rescanned; a line's id changing is the
signal that its count is final. The web client shows the number in its counter
strip next to `breaks`; Android shows it in debug builds only.

### Barge-in

`serverContent.interrupted` → `AudioPlayer.flush()`
(`audio/AudioPlayer.kt:131`, handled at `live/FishingModeController.kt:323`).
Both halves are required: clearing our queue stops future writes, and
`AudioTrack.flush()` drops what the hardware already holds.

### Voice and language

`speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName` (default `Kore`).
Note it is `voice_name` on the wire, not `voiceName`: the converter emits
snake_case, and looking for the camelCase spelling in a capture is a good way
to convince yourself of a voice bug that is not there.

Twelve prebuilt voices are offered (`live/LiveVoices.kt`,
`web/src/ui/HomeScreen.tsx`) and each was verified to open a real session.
That verification is not ceremony: the voice is part of the locked setup, so an
unsupported name does not fall back to a default — it fails the connection at
setup, which reaches the angler as a broken network. Neither client sends the
choice with the Live request; `POST /api/v1/live/session` falls back to
`user.preferences.voice_name`, so Android keeps it in Settings and the web app
offers it on Home.

**No `languageCode`** — native-audio models choose the language themselves and
do not support setting it explicitly. Turkish is steered by the system
instruction instead.

Because the language cannot be pinned in the config, the system instruction
names the two allowed languages explicitly and tells the model that anything
that sounds like a third one is noise — wind, an engine, a radio, or an echo
of its own voice. Without that, unclear audio is enough to make it switch.
Fix the echo first regardless; prompt wording cannot hold a language against a
model listening to itself.

### Subtitles

The Live session answers in **audio only**, so the on-screen translation does
not come from it. Finalised transcript lines go to `POST /api/v1/live/translate`
(`backend/app/services/translation.py`), which runs on a lite text model.
Asking the Live session for a second text modality would cost another output
stream per turn and slow the spoken reply down.

Two things that matter there: translate only **settled** lines — live
transcription arrives as fragments that change several times a second, so a
debounce turns one request per fragment into one per conversational turn — and
expect the text model to be independently contended. The general flash model
returned `503 high demand` and then timed out entirely while the lite model
answered every time, so translation has its own model setting with a fallback.

---

## Video

Sent as throttled **JPEG stills** through `realtimeInput.video`, which is how
the API takes realtime video:

| Setting | Value | Why |
|---|---|---|
| Frame rate | 0.5 fps (user-adjustable) | enough to identify a lure |
| Longest edge | 768 px | ditto |
| JPEG quality | 70 | ditto |
| `mediaResolution` | `MEDIA_RESOLUTION_LOW` | caps vision **tokens per frame** |

`mediaResolution` was added during this audit; it is the documented control for
vision token cost and was previously unset.

**Adaptive policy** (`live/FishingModeController.kt:238`): frames are suppressed
while the model is speaking, while a tool is running, and while reconnecting —
it is not looking at the camera then, and on a three-hour session those frames
are pure cost in tokens, bandwidth and battery. Skipped frames are counted in
`FishingModeState.framesSkipped`.

Throttling happens *before* YUV→JPEG conversion, so a frame we are about to
discard costs no CPU. Frames are rotation-corrected: a sideways image
measurably degrades identification.

The camera is **off by default** — the session is voice-first — and "Look at
this" sends a single frame rather than starting a stream.

### Opening the session, and knowing where it is

A voice-first session that waits to be spoken to feels inert, and the first
version was worse than inert: it opened by asking *"where are you fishing?"* —
a question the phone had already answered. Two things fix that.

**The assistant speaks first.** `primeContext` takes a `requestReply` flag that
maps to `turnComplete`. Normally false, because true interrupts whatever is
generating; set true exactly once, at session open, with two turns — the facts,
then a directive. They are separate turns on purpose: a directive folded into
the facts gets recited.

**It is told where and when.** The client sends its position with the
`/live/session` request. The backend coarsens it to the consented precision,
reverse geocodes it and returns a place name in `user_brief`; the client adds
the local time from the **device** clock, because the phone is standing at the
spot and already knows it. So the opening is "Bostanlı, just gone five, about
an hour of light left" rather than a question.

On a resumed session neither happens. Greeting someone again mid-sentence
after a dropped socket is worse than the drop.

**A silent wire bug made the greeting never happen on Android.** The client
logged `primed context with 2 turn(s) reply=true`, the socket was fine, and
the assistant said nothing: six seconds later the mic opened and the screen
settled into "Listening". `ClientContent.turnComplete` defaulted to `true`
while `LiveJson` sets `encodeDefaults = false`, so asking for a reply produced
a value *equal to the default* and kotlinx.serialization dropped the field:

```
turnComplete = true   ->  {"clientContent":{"turns":[...]}}                     <- gone
turnComplete = false  ->  {"clientContent":{"turns":[...],"turnComplete":false}}
```

The server reads the first as `turnComplete: false` — context accepted,
generation not requested. Only the greeting path was affected, because every
other caller passes `false`, which differs from the default and is written.
The same trap is documented three lines away on `Content.role`. The field is
now required with no default, and `ClientContentRoleTest` asserts the
*serialised bytes*, because the object in memory was always correct. The web
client builds the frame with `JSON.stringify`, which always emits the key, so
this never existed there — one of the few Live-layer bugs that does not port.

**The behaviour needed its own verification.** Unit tests can assert that the
position is sent, that it is coarsened, and that two turns are primed with
`requestReply` — and all of that can be true while the model still opens by
asking where the angler is. `scripts/verify_opening.py` (`make verify-opening`)
closes that gap: it sends the exact turns `primeFishingContext` builds to the
real service, answers tool calls from the real Nominatim and Open-Meteo, and
checks the result in both languages. It found two things:

- **The English session looked up its own spot.** It called
  `find_place("Bostanlı, İzmir")` before `get_current_conditions`, even though
  the coordinates were in the brief — the right place, 0.8 km out, but an extra
  round trip on the one turn where the wait is most audible, and the Turkish
  session never did it. Both instruction files now say to call the conditions
  tools with **no location argument** for where the angler already is;
  `find_place` is for a spot they *name*.
- **Checking the model's wording is the wrong test.** The first version of the
  check asserted that asking about another spot produced a conditions report.
  Then "Çeşme" came back ambiguous (İzmir vs. villages in Aydın and Antalya)
  and the model correctly asked which one — a failing check for correct
  behaviour. Matching phrases instead ("which one", "do you mean") only tests
  the phrase list; the service said *"Are you asking about…"* on the next run
  and failed again. The expectation now comes from what geocoding returned: a
  name that resolves must be answered, an ambiguous one must be asked about and
  must **not** produce a conditions call.

Answering `set_camera` in that harness needs care for the same reason. It is a
device tool with no device here, and answering it generically made the model
append *"I can't see anything right now"* to its opening. That is correct
behaviour for a camera that failed — and incidentally confirms the guard
against describing what it cannot see — but it is not what the script measures,
so the harness returns the client's own success payload.

### Place names: the ranking is the hard part

`find_place` exists because of a real session: asked about the weather at
Bostanlı, the assistant said it could not check it. It had no way to turn a
name into coordinates, and the system instruction now forbids that answer
outright — the tool *is* how it checks.

Two things were measured rather than assumed, and both changed the design:

- **Open-Meteo's geocoder is wrong for these users**, despite being the
  obvious pick (the weather already comes from there, and it is keyless).
  "Bostanlı" returned five inland villages — Mardin, Edirne, Tunceli, Afyon,
  Adıyaman — and never the İzmir waterfront. "Kilyos", one of Istanbul's
  best-known beaches, returned nothing. It also needs exact Turkish
  diacritics: "Bostanli" matched nothing.
- **Nominatim finds them but ranks them badly.** For "Bostanlı" the İzmir
  neighbourhood came back **tenth of twelve**, behind villages 300-1400 km
  inland, because global "importance" has nothing to do with which one this
  angler means.

So the ranking is ours: candidates are re-sorted by distance from where the
angler actually is (device position → active session → saved spot), which put
the right answer first in every case tested. The guard is a ratio as well as a
radius — the nearest wins only if it is both close and decisively closer than
the runner-up — so two spots 20 km away in opposite directions stay ambiguous
instead of being decided by a rounding error. An ambiguous result returns
options and an instruction to ask, never a pick.

Why so much care for a geocoder: the failure mode is not "no answer", it is
*confidently describing the sea state of a village 1000 km from the sea*. The
marine endpoint's "no sea data for these coordinates" note is the backstop, and
the system instruction tells the model to surface it rather than narrate
around it.

Nominatim is free and asks for three things, all honoured in the provider: an
identifying User-Agent, at most one request a second (a lock, not luck), and
caching — a place name does not move, so the TTL is a day.

### The model opens its own eyes

Off by default creates an obvious hole: the angler says "do you see this?" and
the model cannot, so the honest answer is "press the camera button", which is
useless to someone holding a rod in one hand. So the model is given
`set_camera(enabled)` and told in the system instruction that its eyes start
closed and opening them is its job, not the user's.

It is the only tool the **device** executes. The declaration still lives in
`backend/app/tools/declarations.py`, because the declaration is what the model
sees and it has to be minted with the session token — but the name is in
`DEVICE_TOOLS`, and each client intercepts it before posting a batch
(`executeDeviceTool`, both `FishingModeController`s).

Three things about it are not obvious and all three were worth the trouble:

- **The call is answered only after a frame has gone out.** Returning as soon
  as the camera is asked to start makes the model talk about an image it does
  not have yet; it either stalls or invents. So the handler suspends on the
  stream's first frame, with a 2.5 s timeout — because an *unanswered* tool
  call hangs the conversation permanently, which is far worse than a slow one.
- **The adaptive skip had to learn an exception.** Frames are normally
  suppressed during a tool call, and enabling the camera *is* a tool call, so
  the first implementation starved itself: the camera ran, every frame was
  skipped, and the call timed out. A pending waiter now overrides the skip.
- **A failure must surface as a failure.** A refused permission returns
  `device_tool_failed` with `instruction_to_model` telling the model to say it
  cannot see and describe nothing. A model told "camera on" that sees nothing
  will describe a fish that is not there.

The model is also told to call `set_camera(enabled: false)` when it is done
looking, which matters more here than it sounds: the audio+video session
ceiling is **2 minutes** against 15 for audio alone, so sight is something it
takes and gives back rather than leaves on.

This also exposed a bug in "Look at this": `captureStill` returns the most
recent *streamed* frame, so with the camera off it returned null and the button
silently sent nothing. It now starts the camera long enough for one frame and
puts it back.

---

## Tools

Real Live API function calling. There is no text-parsing anywhere; the
`toolCall` / `toolResponse` message types are used as documented.

```
Gemini Live  ──{"toolCall":{"functionCalls":[{id,name,args}]}}──▶  Android
                                                                     │
                      POST /api/v1/tools/execute-batch (authenticated)
                                                                     ▼
                                              FishingToolRegistry.execute()
                                              validate → execute → audit
                                                                     │
Gemini Live ◀──{"toolResponse":{"functionResponses":[{id,name,response}]}}
```

| Step | Code |
|---|---|
| Receive `toolCall` | `android/.../live/GeminiLiveSession.kt:374` |
| Forward batch | `backend/app/api/v1/tools.py:57` |
| Validate + execute + audit | `backend/app/tools/registry.py:586` |
| Return `toolResponse` | `android/.../live/GeminiLiveSession.kt:216` |

Twelve functions: `get_current_conditions`, `get_conditions_forecast`,
`get_sun_times`, `get_tackle_inventory`, `get_recent_catches`,
`get_recent_sessions`, `recommend_setup`, `start_fishing_session`,
`finish_fishing_session`, `log_catch`, `add_session_observation`,
`get_fishing_regulations`.

Properties that matter:

* **Batching.** One `toolCall` may carry several `functionCalls`, and the
  server expects **one** `toolResponse` containing all of them.
* **Ids are echoed.** `FunctionResponse.id` = `FunctionCall.id`.
* **Every call is answered.** Network failure synthesises an error response per
  call; an unanswered `toolCall` hangs the conversation — the assistant goes
  silent and the angler assumes a crash. Tested in `ToolOrchestratorTest`.
* **Nothing executes unvalidated.** Pydantic models with `extra="forbid"` and
  bounded numerics; no model-generated code or SQL is ever run.
* **`instruction_to_model`** travels in the response — "NOT VERIFIED, do not
  state this as fact", "no recorded catch, say so plainly" — restating the
  system instruction at call time.

### Asynchronous function calling — honest status

`behavior: "NON_BLOCKING"` and `FunctionResponse.scheduling`
(`INTERRUPT` / `WHEN_IDLE` / `SILENT`) are **implemented** and wired end to end
(`backend/app/tools/declarations.py:368`,
`android/.../live/protocol/Wire.kt`), gated by
`GEMINI_ASYNC_FUNCTION_CALLING`, **default off**.

Why off: the official tool-use page states only that *"Asynchronous function
calling is not yet supported in Gemini 3.1 Flash Live"* and **does not state
support for `gemini-3.8-live`**. Turning on an unconfirmed capability for a
model that will be inspected is the wrong trade, so it is a documented switch.

It would also change little here: the slowest tool measured **318 ms**
(a weather fetch) and database tools run in 1–10 ms. The one genuine candidate
is `get_fishing_regulations`, which can take seconds when it falls through to a
Search-grounded lookup — so `SLOW_TOOLS` contains exactly that tool, and only
it is declared `NON_BLOCKING` when the flag is on.

---

## Search

The **built-in** tool, not a custom HTTP search injecting text:

```json
"tools": [ { "functionDeclarations": [ … ] }, { "googleSearch": {} } ]
```

Grounding therefore happens inside the Live session, and the citations come
back as `serverContent.groundingMetadata`. Those are now parsed into
`LiveEvent.Grounding` (`android/.../live/GeminiLiveSession.kt:337`) and held in
`FishingModeState.groundingSources` — previously they were modelled and then
discarded.

That matters most for regulations, where the user needs to see the source.
Server-side, `GroundedRegulationProvider` additionally uses
`google_search` through `generate_content` and returns the citations, but
**always flagged `verified=false`**: a search result is a lead, not an official
circular.

Search is not used for static fishing knowledge — the system instruction tells
the model not to call tools gratuitously.

---

## Session management

| Documented limit | Value |
|---|---|
| Audio-only session | 15 minutes |
| Audio **+ video** session | **2 minutes** |
| Single connection | ~10 minutes |
| Resumption handle validity | 2 hours after termination |

Two minutes is shorter than one conversation about a lure, so both mitigations
are always on.

**Context window compression** — `contextWindowCompression` with
`slidingWindow.targetTokens` 8 000 and `triggerTokens` 16 000. The
session-management documentation states this extends sessions "to an unlimited
amount of time".

**Session resumption** — `"sessionResumption": {}` enables it; the server
pushes `sessionResumptionUpdate.newHandle`
(`android/.../live/GeminiLiveSession.kt:389`), which the client stores and
replays on reconnect. This is the most important single piece of client state:
with it a dropped connection continues the same conversation.

**`goAway` is routine** (`android/.../live/GeminiLiveSession.kt:397`). The
client reconnects **proactively**, 1.5 s before the deadline, so the new socket
is up before the old one closes and the user hears no gap. The UI shows
"Reconnecting…", never an error.

**Credential renewal** — tokens last 30 minutes, sessions longer. On
`token_expired` the controller re-mints and resumes with the handle
(`live/FishingModeController.kt:288`).

**Context priming** — the angler's history reaches a new session through
`clientContent` with `turnComplete: false`
(`android/.../live/GeminiLiveSession.kt:200`), the documented way to "add
messages to the model context in order". `turnComplete: true` is never used for
this, because it *unconditionally interrupts generation*. Skipped on a resumed
session, where the context is already present.

---

## Error handling

```
IDLE → CONNECTING → LISTENING ⇄ SPEAKING
                        │  ▲
                 interrupted │
                        ▼  │
                    TOOL_CALL → THINKING

  goAway / drop ──▶ RECONNECTING ──(handle)──┐
  token_expired ──▶ re-mint ──▶ RECONNECTING ┘
  401/403       ──▶ re-mint (retrying the socket cannot help)
  exhausted     ──▶ ERROR, user-facing message
```

Handled explicitly: socket failure, 401/403, `goAway`, unexpected close,
network change, microphone loss, audio-focus loss (a phone call), camera
failure, app lifecycle, token expiry. Backoff is exponential with jitter, which
avoids a thundering herd when many clients on one cell tower drop together.

Unknown server fields are tolerated (`ignoreUnknownKeys`) rather than fatal — a
new API field must not kill a live conversation.

---

## Security

See [SECURITY.md](SECURITY.md). Summary: no Gemini credential in the APK;
constrained ephemeral tokens; nothing persisted on device; redaction of keys,
tokens, raw media and precise coordinates from all logs; provider exception
*messages* never propagated, because a Gemini error can contain the key in a
URL.

---

## Current API compatibility

| Capability | State | Note |
|---|---|---|
| `gemini-3.8-live` (GA) | ✅ | |
| Native audio in/out | ✅ | 16 kHz in, 24 kHz out |
| Realtime video frames | ✅ | throttled + adaptive |
| `mediaResolution` | ✅ | `LOW`, added in this audit |
| Function calling | ✅ | 12 tools, batched, ids echoed |
| `googleSearch` built-in tool | ⚙ off by default | needs Search quota; surfaces `groundingMetadata` when on |
| Session resumption | ✅ | proactive reconnect on `goAway` |
| Context window compression | ✅ | sliding window |
| Ephemeral tokens | ✅ | constrained + locked fields |
| Input/output transcription | ✅ | |
| `interimInputTranscription` | ✅ | added in this audit |
| `waitingForInput` | ✅ | added in this audit |
| Barge-in / `interrupted` | ✅ | queue + AudioTrack flush |
| Automatic VAD | ✅ | tuned for a windy shore |
| `activityHandling`, `turnCoverage` | ✅ | explicit, added in this audit |
| Proactive audio | ⛔ | documented as supported; **rejected by the live service** |
| Affective dialog | ⛔ | documented as supported; **fatal on first generation** |
| `clientContent` context priming | ✅ | added in this audit |
| `usageMetadata` | ✅ | surfaced for cost |
| `thinkingLevel` | ⛔ by design | unsupported on 3.8 Live; omitted *and* locked |
| Async function calling | ⚙ implemented, off | support for 3.8 Live not documented |
| Code execution | ⛔ | not supported in Live API |
| URL context | ⛔ | not supported in Live API |

---

## Verifying it for real

Unit tests never contact Google. To prove the integration end to end:

```bash
export GEMINI_API_KEY=...
make verify-live
```

`backend/scripts/verify_live_api.py` opens a real session using **this
project's production config** and checks, in order: connect → audio accepted →
native audio received → video frame accepted → a real `toolCall` is issued →
`toolResponse` returned → conversation continues → resumption handle issued →
reconnect with that handle and confirm context carried over.
