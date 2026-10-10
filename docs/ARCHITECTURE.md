# Architecture

## Principle

> Gemini Live is not a feature bolted onto the application. Gemini Live **is**
> the interaction layer.

Concretely: `FishingModeController` owns the microphone, the camera, the tool
loop and the reconnect policy, and reduces all of it to one `FishingModeState`
the UI renders. The UI has no idea a WebSocket exists. The domain has no idea
Gemini exists.

---

## Layering

```
UI (Compose)
   ▼
ViewModel  ──────────────┐
   ▼                     │
FishingModeController ───┤   the only place that knows about Live sessions
   ▼                     │
LiveAiSession (interface)│
   ▼                     │
GeminiLiveSession        │   OkHttp WebSocket, protocol framing
MockLiveAiSession        │   test double

Repositories ────────────┤   AppResult<T>, never exceptions
   ▼                     │
Room (local) + Retrofit (remote)
```

Two rules hold this together:

1. **The domain never depends on Gemini.** Species normalisation, the
   recommendation engine and the memory digests are plain functions over plain
   data. They are unit-tested without a model, and would survive swapping the
   AI provider entirely.
2. **Errors do not propagate as exceptions.** `apiCall()` converts every
   network failure into `AppResult.Err(AppError)` with a user-presentable
   message. That makes "no stack traces in the UI" true by construction rather
   than by discipline.

---

## The four abstractions

### `LiveAiSession`

```kotlin
interface LiveAiSession {
    val connectionState: StateFlow<LiveConnectionState>
    val events: Flow<LiveEvent>
    suspend fun connect(credentials: LiveCredentials, resumeHandle: String? = null)
    suspend fun sendAudio(pcm: ByteArray, length: Int = pcm.size)
    suspend fun sendImage(jpeg: ByteArray)
    suspend fun sendText(text: String)
    suspend fun sendToolResponses(responses: List<ToolResult>)
    suspend fun signalAudioStreamEnd()
    suspend fun disconnect()
    fun release()
}
```

Why it earns its place: `MockLiveAiSession` can drive `goAway`, interruption,
a tool call with bad arguments, and token expiry on demand. Those are the
behaviours most likely to break in the field and the least practical to provoke
against the live service — so they are the ones that must be testable.

### `CameraSource`

```kotlin
interface CameraSource {
    val state: StateFlow<CameraState>
    val facing: StateFlow<CameraFacing>
    val supportsSwitching: Boolean
    fun frames(config: FrameConfig = FrameConfig()): Flow<CameraFrame>
    suspend fun captureStill(config: FrameConfig = FrameConfig()): CameraFrame?
    suspend fun switchCamera()
    fun release()
}
```

`PhoneCameraSource` wraps CameraX. The Gemini layer depends on the interface,
so `SmartGlassesCameraSource` or `ExternalCameraSource` can be added later
without touching the AI pipeline. Frames are JPEG stills by design — see
[GEMINI_LIVE.md](GEMINI_LIVE.md#4-video).

### `FishingToolRegistry` (backend)

Each tool has a name, a description written for the model, a JSON schema, a
Pydantic validation model, a handler, error handling and an audit row. The
registry refuses to construct itself if a declared tool has no handler — a
mismatch that would otherwise surface as a 500 mid-conversation.

### `WakeWordEngine`

An interface with a deliberate no-op default, because two constraints are real:
Android will not permit arbitrary background microphone access, and we will not
hold a cloud connection open just to spot a keyword. Once Fishing Mode is
running no wake word is needed anyway — server-side VAD handles turn-taking.
See [ROADMAP.md](ROADMAP.md).

---

## Why the device executes tools

Gemini calls back to whoever holds the socket, and that is the device. But the
device has neither the database nor the right to decide what a tool returns. So
a `toolCall` becomes an authenticated HTTPS request to
`/api/v1/tools/execute-batch`, and the backend validates and executes it.

This keeps three properties at once: media stays low-latency (direct to
Gemini), data stays authoritative (server-side), and the tool surface stays
locked (it is baked into the token).

---

## Offline-first

Fishing happens where signal does not. The rule from the brief — *do not
silently lose data* — is implemented as:

* **Reads** always come from Room, so every screen renders instantly and works
  with no network at all.
* **Writes** go to Room first, then to the network. A failed network write is
  queued in `pending_ops` and replayed by `SyncManager`, which drains strictly
  oldest-first and **stops at the first failure** (a correction must not be
  applied before the catch it corrects exists).
* Starting a session offline creates it locally; the angler is already on the
  shore, and a session that refuses to start because of signal is worse than
  one that syncs later.
* Queued writes are **shown** in a banner, not hidden, so the angler knows
  their catch is safe.
* `refreshAll()` tolerates partial success: if tackle loads but catches time
  out, the tackle box still works.

---

## Testing strategy

| Layer | Approach | Count |
|---|---|---|
| Backend domain | Direct unit tests (species, recommendation, memory) | — |
| Backend API | `httpx.ASGITransport` against the real app, SQLite in memory | — |
| Backend external | `respx` mocks Open-Meteo; weather provider swappable | — |
| Backend Gemini | `FakeLiveTokenService` records what was locked | — |
| **Backend total** | | **231** |
| Android protocol | Literal JSON assertions, both directions | 12 |
| Android session | Setup frame, resumption handle, backoff, `goAway` timing | 9 |
| Android state machine | `MockLiveAiSession` drives the whole conversation | 16 |
| Android tool loop | Orchestrator's "always answer" contract | 5 |
| Android mapping | Timestamp fallbacks, AI provenance, photos | 9 |
| Android transcript | Fragment merging, bounded growth | 4 |
| **Android total** | | **55** |

**No automated test contacts Gemini or a live weather API.** Two reasons: cost
per run, and determinism — a test that depends on a model's wording fails for
reasons that are not bugs.

Two tests exist specifically to catch silent drift:

* `test_migrations_match_models` — a column added to the models without a
  migration passes on a freshly-created test DB and then fails in production.
* `test_every_declared_arg_matches_a_pydantic_field` — a tool declaration that
  drifts from its validation model only breaks at runtime, mid-conversation.

### A measurement note

Coverage is configured with `concurrency = ["greenlet", "thread"]`. Without it,
coverage.py cannot see through SQLAlchemy's asyncio greenlets and silently
under-reports any code path that awaits the database — it reported 48% for a
module whose tests assert on values only that module produces. The real figure
is **91%**.

---

## Decisions worth recording

| Decision | Reason |
|---|---|
| Raw WebSocket, not the Kotlin GenAI SDK | The SDK is explicitly unsupported on Android |
| String UUID primary keys | The client can create rows offline without id remapping |
| `UtcDateTime` type decorator | Postgres returns aware datetimes, SQLite naive ones; normalising in one place fixed a crash that appeared only under SQLite |
| Open-Meteo | No API key (one less secret, and a demo that cannot fail on a missing credential) **and** it publishes marine data: wave height, swell period, sea-surface temperature |
| Scored recommendation engine, not a big prompt | Gear choice depends on the user's real inventory and history, which live in our data, not the model's weights — and a function is testable |
| Deterministic session reports | Shown verbatim in the UI and offline; must be reproducible and cheap |
| No tackle seeded at registration | Recommending gear the angler does not own is worse than recommending nothing. Onboarding offers opt-in templates instead. |
| `populate_existing=True` on session reads | The identity map otherwise returned a stale `catches` collection, so a report built in the same request as a logged catch under-counted |
| Eager-load catch photos | Serialising a session detail lazily triggered `MissingGreenlet` in the async context |
| One repository for tackle/sessions/catches | They are written together (logging a catch updates the session count); splitting them would need a cross-repository transaction |
| `android.newDsl=false` | AGP 9's new DSL is incompatible with `kotlin-android`, and KSP — which Room *and* Hilt need — is not yet wired for built-in Kotlin |

---

## Regulatory data

`services/regulations.py` inverts the usual contract: the default answer is
"I cannot confirm this", and `verified=True` is set **only** for curated data
carrying a `source_url` and an `effective_from` date.

Three layers:

1. `StaticRegulationProvider` — per-region JSON, transcribed from the official
   circular. `tr.json` carries Çizelge 7 of circular 2024/21
   (1/9/2024–31/8/2028); **every entry must have a `source_url` and an
   `effective_from`**, and a species the circular omits is omitted here
   too so the tool keeps refusing for it.
2. `GroundedRegulationProvider` — Gemini with Google Search grounding, returned
   *with its sources* and still flagged unverified.
3. Honest refusal, naming the responsible authority.

A fabricated size limit is not a bad answer; it is a fine for the user. This is
also the one place where the architecture is deliberately less capable than it
could be.

---

## Extension points

Designed for, not built:

| Future | Where it plugs in |
|---|---|
| Smart glasses / external camera | `CameraSource` |
| On-device wake word | `WakeWordEngine` |
| Tides, marine forecasts, paid weather | `WeatherProvider` |
| Regional regulations | `RegulationProvider` + `resources/regulations/<region>.json` |
| More languages | `resources/system_instruction.<lang>.md` + `values-<lang>/` |
| Different AI provider | `LiveAiSession` |
