# Research Brief — "I Took Gemini Live Fishing"

Evidence collection for a technical article. **Nothing here is an estimate
presented as a measurement.** Where a number was measured, the method is
given; where it was not, the section says *not measured*.

Compiled 2026-10-09 against the working tree. No application code was changed
to produce this document.

**A note on provenance before anything else:** this repository has **zero
commits** (`git rev-list --count HEAD` → 0; everything is staged on `master`).
There is therefore no commit history to cite, and no finding below is
attributed to one. Evidence is drawn from source files, test results, the
running backend's structured log, on-device `logcat`, and probes run against
the real Gemini service during development.

---

## 1. Executive summary

FishMate AI is a hands-free fishing companion in which Gemini Live is the
interaction layer rather than a feature bolted onto a CRUD app. It is three
deliverables: a FastAPI backend (`backend/`), a Compose Android app
(`android/`), and a Vite/React web client (`web/`) that mirrors the Android
module names so the two clients can be read against each other.

**A real Gemini Live session has connected and completed voice interactions,
repeatedly, on a physical Galaxy A35 and from scripts on a workstation.** This
is not simulated. Direct evidence appears in §6.

The most useful material for an article is not the integration itself — the
Live API is, in the end, a WebSocket — but the gap between what the published
documentation says and what the service does, and a class of bug that only
appears when a voice assistant leaves the desk: audio that arrives 3.3× faster
than it can be played, a serialiser that silently deletes the field that asks
the model to speak, a geocoder that ranks the right answer tenth of twelve, and
a marine data grid that reports sea state 25 km inland.

---

## 2. Architecture and implementation

### 2.1 Model and SDK

| Item | Value | Evidence |
|---|---|---|
| Live model | `gemini-3.8-live` | `backend/app/config.py:27` |
| Text model (translation, grounded regulations) | `gemini-3.8-flash` | `backend/app/config.py:28` |
| API version | `v1beta` | `backend/app/config.py:75` |
| Python SDK | `google-genai` **2.28.0** | `import google.genai; __version__` |
| Android SDK | **none** — raw WebSocket | see §2.3 |

### 2.2 Session establishment

```
Android ──POST /api/v1/live/session──▶ backend ──auth_tokens.create──▶ Gemini
Android ──wss://…BidiGenerateContentConstrained?access_token=…───────▶ Gemini
Gemini  ──toolCall──▶ Android ──POST /tools/execute-batch──▶ backend ──▶ DB/APIs
```

Media goes straight to Google; the backend never proxies audio or video. It
mints a short-lived **constrained** ephemeral token
(`backend/app/services/live_tokens.py:314`, `auth_tokens.create`) and executes
tool calls. The constrained endpoint is
`/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained`
(`live_tokens.py:54`).

`LOCKED_ADDITIONAL_FIELDS` (`live_tokens.py:69`) pins configuration into the
token so a tampered client cannot change the model, the system instruction or
the thinking configuration.

### 2.3 Audio

| Parameter | Value | File |
|---|---|---|
| Input | 16 kHz PCM16 mono | `android/app/src/main/java/com/fishmate/ai/audio/AudioCapture.kt:34` |
| Output | 24 kHz PCM16 mono | `android/app/src/main/java/com/fishmate/ai/audio/AudioPlayer.kt` |
| Frame | 20 ms | `audio/OutputLevelMeter.kt:23` |
| Capture source | `VOICE_COMMUNICATION` | `audio/AudioCapture.kt` |
| Echo control | `AcousticEchoCanceler`, `NoiseSuppressor`, `AutomaticGainControl` attached to `AudioRecord.audioSessionId`, plus `audio/EchoGate.kt` | `audio/AudioCapture.kt` |
| Playback | `AudioTrack` MODE_STREAM, blocking write | `audio/AudioPlayer.kt` |

Input and output run at **different sample rates**, both dictated by the
service.

### 2.4 Camera

`CameraX ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
(`android/app/src/main/java/com/fishmate/ai/camera/PhoneCameraSource.kt`).
Frame budget defaults (`camera/CameraSource.kt:64`):

```kotlin
FrameConfig(framesPerSecond = 0.5, maxDimensionPx = 768, jpegQuality = 70)
```

`mediaResolution` is `MEDIA_RESOLUTION_LOW` (`backend/app/config.py:63`) —
chosen for vision-token cost, not fidelity.

### 2.5 Function calling

14 declarations in `backend/app/tools/declarations.py`. Tool calls arrive on the
client, which posts them to `/api/v1/tools/execute-batch`; the registry
(`backend/app/tools/registry.py`) executes them against the DB and external
APIs.

**One tool runs on the device.** `set_camera` is *declared* server-side (the
declaration must be minted with the token, since that is what the model sees)
but listed in `DEVICE_TOOLS` and executed by whichever client owns the camera.
Three guards keep the split honest (`registry.py:649-658`): the registry
refuses to start if a non-device tool lacks a handler *or* if a device tool has
one, and `/tools/execute-batch` answers a device tool with `device_tool` rather
than "unknown tool".

### 2.6 Session lifecycle

- Session resumption enabled (`session_resumption: {}`), handles stored in
  `FishingModeState.resumeHandle`.
- Context window compression: trigger 16 000 tokens, sliding window target
  8 000 (`live_tokens.py:63-64`).
- Server VAD with `START_OF_ACTIVITY_INTERRUPTS`, `silence_duration_ms = 550`,
  `prefix_padding_ms = 300` (`config.py:46,49`).
- `FishingModeController` owns mic, camera, tool loop and reconnection and
  reduces all of it to one `FishingModeState`.

### 2.7 Secrets

`GEMINI_API_KEY` lives in the backend environment (`.env`, `config.py:26`) and
never reaches a client. Clients receive only ephemeral tokens.
`android/.../data/remote/AuthStore.kt` states and enforces the rule: no Gemini
credential is ever persisted on device.

---

## 3. Most significant engineering findings

### Finding 1 — The serialiser deleted the field that asks the model to speak

**Technical explanation.** The Android client builds wire frames with
kotlinx.serialization. `LiveJson` sets `encodeDefaults = false`
(`android/.../live/protocol/Wire.kt:25`). `ClientContent.turnComplete`
defaulted to `true`. Asking for a reply therefore produced a value *equal to
the default*, and the field was omitted from the JSON entirely:

```
turnComplete = true   ->  {"clientContent":{"turns":[...]}}                      <- field gone
turnComplete = false  ->  {"clientContent":{"turns":[...],"turnComplete":false}}
```

The server reads the first as `turnComplete: false`: context accepted,
generation **not** requested. The assistant stayed silent through every
session opening while the client logged a successful send and the object in
memory was perfectly correct. Only the `false` path — every other caller —
worked, because `false` differs from the default and is written.

**Evidence.** Measured by serialising both values under each configuration (a
temporary probe test, since removed). On-device log of a failing session:

```
14:38:30  POST /api/v1/live/session → 200
14:38:32  I/GeminiLive: primed context with 2 turn(s) reply=true
14:38:38  W/FishingMode: no greeting within 6000ms; opening the mic
```

**Validation.** Fixed by removing the default (`Wire.kt:122`). Pinned by
`ClientContentRoleTest > asking for a reply really puts turnComplete true on
the wire`, which asserts the *serialised string*. The test was verified to fail
against the reintroduced defect by running the full suite, not a filtered
subset.

**Relevance.** The single best story in the project: a correct object, a
successful send, a healthy socket, and a silent assistant. It is also a trap
specific to Kotlin — the web client builds the frame with `JSON.stringify`,
which always emits the key, so the bug never existed there. One of the few
Live-layer bugs that does **not** port between the two clients.

---

### Finding 2 — The service generates audio 3.3× faster than real time

**Technical explanation.** `generationComplete` means *the model has finished
generating*, not *the speaker has finished talking*. For native-audio output
the gap is large.

**Evidence.** Probe against the real service, one three-sentence Turkish
answer:

```
audio received      : 791 040 bytes = 16.48 s of speech
first audio chunk at: 1.21 s
last audio chunk at : 5.02 s
generationComplete  : 5.04 s
→ 12.65 s of audio still unplayed when generationComplete fired
```

**Validation.** Two consequences were found and fixed:

1. The client left the `SPEAKING` phase on `generationComplete`, so the
   avatar's mouth froze a couple of syllables in and the screen read
   "Listening" over a talking assistant. The phase now follows
   `AudioPlayer.isAudible`, which compares the playback head against everything
   handed to the device. Pinned by `FishingModeControllerTest > speaking lasts
   until the speaker goes quiet, not until generation ends`, verified to fail
   against the old behaviour.
2. The playback queue was capped at 8 s (`MAX_QUEUED_BYTES = 24_000 * 2 * 8`).
   A single turn delivered 16.48 s, so the cap was hit mid-sentence and the
   oldest chunks were dropped — the angler never heard part of the reply. This
   happened on the device during a real test session:
   `10-09 14:47:53 W/AudioPlayer: playback queue over 384000B, dropping oldest`.
   The cap is now 60 s and the log line is an error.

**Relevance.** This is the finding most likely to bite other developers, and
it is invisible on a desk: with a short answer the queue never fills and the
mouth freezes for only a moment.

---

### Finding 3 — Three documented Live settings do not work as documented

**Technical explanation and evidence** (`backend/app/services/live_tokens.py`
docstrings, each established by running it):

| Setting | Documentation | Observed |
|---|---|---|
| `proactivity` | listed as supported on Gemini 3.8 Live | rejected at setup: `Unknown name "proactivity" at 'setup'`, socket closes 1007 |
| `enable_affective_dialog` | listed as supported | **accepted at setup**, then the first generation turn dies with 1007 |
| `thinkingLevel` | — | must be omitted for `gemini-3.8-live`; also in `LOCKED_ADDITIONAL_FIELDS` |

**Relevance.** `enable_affective_dialog` is the worst failure mode in the set:
it looks configured and breaks on the first reply. An article aimed at
developers should lead with this.

---

### Finding 4 — The wire `setup` is not the SDK's shape

Sending the SDK's `config` wrapper fails with `Unknown name "config" at
'setup'`. `model` needs the `models/` prefix;
`responseModalities` / `speechConfig` / `mediaResolution` live under
`generationConfig`. `build_wire_setup()` delegates to the SDK's own
`_LiveConnectParameters_to_mldev` converter rather than hand-rolling it
(`live_tokens.py:203+`). Pinned by
`backend/tests/test_live_tokens.py::TestWireSetupShape`.

---

### Finding 5 — `session.receive()` is a per-turn generator

It ends at `turnComplete`, and the model ends its turn when it issues a tool
call — so the spoken answer needs a *fresh* `receive()`. Holding one iterator
across turns silently yields zero audio. Documented at
`backend/scripts/verify_live_api.py:12-17` as a behaviour that, got wrong,
"makes the script lie".

---

### Finding 6 — On a loudspeaker the model language-detects its own echo

**Technical explanation.** `VOICE_COMMUNICATION` alone is not enough. The
model's own voice reaches the mic, VAD calls it a barge-in, and the
conversation collapses. It also makes the model **switch to languages nobody
spoke**, because it language-detects its own distorted output.

**Evidence.** **32 interruptions in 50 s on a Galaxy A35** (recorded in
`CLAUDE.md`, "Gemini Live: facts established by running it").

**Validation.** Fixed by explicitly attaching `AcousticEchoCanceler` /
`NoiseSuppressor` (often *not* attached despite the audio source) plus
`audio/EchoGate.kt`, which gates the mic against a measured echo floor on
speaker only. Thresholds pinned by `EchoGateTest`; two plausible-looking
estimators both failed on-device first. Muting during playback was rejected as
a fix because it removes barge-in.

**Relevance.** "Suspect echo before you suspect the prompt" is a genuinely
non-obvious debugging lesson.

---

### Finding 7 — Geocoding ranking, not geocoding, is the hard part

**Technical explanation.** Asked "how's the weather at Bostanlı?", the
assistant used to answer that it could not check. Two measured facts shaped
`backend/app/services/geocoding.py`:

- **Open-Meteo's geocoder is wrong for these users**, despite being the obvious
  pick (same vendor as the weather, no key). "Bostanlı" returned five inland
  villages — Mardin, Edirne, Tunceli, Afyon, Adıyaman — and never the İzmir
  waterfront. "Kilyos", one of Istanbul's best-known beaches, returned nothing.
  It is also diacritic-sensitive: "Bostanli" matched nothing.
- **Nominatim finds them but ranks them badly.** For "Bostanlı" the İzmir
  neighbourhood came back **tenth of twelve**, behind villages 300–1400 km
  inland, because a global importance score has nothing to do with which one
  this angler means.

**Validation.** Candidates are re-sorted by distance from the angler; the
guard is a ratio as well as a radius, so two spots 20 km away in opposite
directions stay ambiguous instead of being decided by a rounding error. Verified
against the live service: `find_place("Bostanlı")` → Bostanlı Mahallesi,
Karşıyaka, İzmir, 5.5 km; `find_place("Kilyos")` → Kumköy (Kilyos), Sarıyer,
İstanbul, 353 km. 16 unit tests in `backend/tests/test_geocoding.py`.

**Relevance.** The failure being designed against is not "no answer" — it is
*confidently describing the sea state of a village 1000 km from the sea*.

---

### Finding 8 — Marine data availability is not a proximity signal

**Technical explanation.** The product needed "is the angler at the water?" so
the greeting could adapt. `sea_data_available` looked like the answer, and the
code even asserted that "the marine grid excludes inland points". Measured, it
does not: Open-Meteo's marine grid snaps to the nearest cell.

**Evidence.** Probe from the Bostanlı waterfront along two inland bearings:

```
inland ESE:  10 km → wave data present ·  15 km → absent
inland SSW:  25 km → wave_height 0.04, SST 22.7  (still "sea")
grid spacing ≈ 0.0833° ≈ 9 km
```

**Validation.** The flag is surfaced to the model as a **binary** fact with an
explicit instruction never to express it as a distance, because at 9 km grid
spacing the implied distance is meaningless at close range. The negative is
reliable: no sea state means genuinely inland. Verified end to end — see §5.

**Relevance.** A good example of a plausible proxy that a five-minute
measurement destroys.

---

### Finding 9 — A safety default that made the feature never work

**Technical explanation.** Regulations default to `verified=false` and refuse,
because a wrong size limit is a fine for the user. `resources/regulations/tr.json`
shipped **empty** for that reason. The fallback was a Google-Search-grounded
lookup — which needs its own search quota.

**Evidence.** In the field, every regulation question degraded to a refusal.
Backend log: `regulations.grounded_failed error_type=ClientError`. Reproduced
directly: `429 RESOURCE_EXHAUSTED … You exceeded your current quota`.

**Validation.** The curated file now carries Çizelge 7 of circular 2024/21
(1/9/2024–31/8/2028), transcribed from the Resmî Gazete text on mevzuat.gov.tr,
with a `source_url` and `effective_from` on every entry. A species the circular
does not list is still absent, so the tool still refuses for it. A failed
lookup is now distinct from an absent rule (`lookup_unavailable`).

**Relevance.** "The safe default was so safe the feature never ran" is a real
product lesson, and the fix is about *provenance*, not about trusting a model.

---

### Finding 10 — A common-name collision that would have cost the user a fine

**Technical explanation.** The species catalogue labelled *Seriola dumerili* as
"Akya". In the official circular, **Akya is *Lichia amia* at 40 cm** and
**Sarıkuyruk is *Seriola dumerili* at 30 cm** — two different fish, 10 cm
apart. Regulation entries are keyed off this catalogue, so an angler saying
"akya" would have been told 30 cm while holding an undersized fish.

**Evidence.** `backend/app/services/species.py` (entry now split); circular
Çizelge 7 rows for both species.

**Validation.** *Lichia amia* added as its own species; entries keyed by
scientific key, never by common name. Pinned by
`test_akya_and_sarikuyruk_do_not_share_a_limit`.

**Relevance.** The clearest illustration of why the regulations module exists:
the dangerous output is not a refusal, it is a confident wrong number.

---

### Finding 11 — Tests that encode a workaround as an invariant

When the regulation file was populated, **7 tests failed**. All of them had
encoded "the data file is empty" as the guarantee, including one named
`test_regulations_never_report_verified_without_citation` whose assertion was
simply `verified is False`. The real invariant — *a verified answer carries a
citation and an effective date, and every shipped entry has both* — now
replaces it (`test_every_curated_entry_carries_a_citation`).

**Relevance.** A good cautionary tale: the tests passed for years and
guaranteed nothing about the property they were named after.

---

## 4. Bugs and fixes (consolidated)

| Bug | Root cause | Fix | Validation |
|---|---|---|---|
| Assistant never spoke first | `turnComplete` stripped by `encodeDefaults=false` | default removed | wire-shape test, verified to fail against the defect |
| Avatar mouth froze mid-speech | phase left `SPEAKING` on `generationComplete`, 12.65 s early | phase follows `AudioPlayer.isAudible` | controller test, verified to fail against the defect |
| Part of a reply never heard | 8 s playback cap vs 16.5 s turn | cap raised to 60 s, drop logged as error | measured turn size; on-device log |
| Greeting killed by ambient noise | mic opened before the greeting; VAD interrupts on any activity | mic waits for first audio chunk, 6 s bound | 3 controller tests |
| Opening asked "where are you fishing?" | position never sent; no reply requested | position in token request + two primed turns | `make verify-opening` against the real service |
| "I can't check Bostanlı" | no name→coordinate path | `find_place` + distance re-ranking | 16 unit tests + live verification |
| Regulation questions always refused | empty curated file + exhausted grounding quota | circular transcribed with citations | 297 backend tests; live Q&A (§5) |
| "Akya" → wrong legal size | catalogue common-name collision | species split, keyed scientifically | dedicated test |
| Subtitles translate Turkish→Turkish | subtitle language fixed from the account setting at session start; the conversation language can drift | **not fixed** — see §6 | — |
| Every launch required a fresh login | `FishMateNavHost` defaulted `startDestination` to onboarding and the root composable passed no argument, ignoring a valid 30-day token in DataStore | startup decision in `RootViewModel`; `UnauthorizedInterceptor` clears a rejected token | 8 tests in `RootViewModelTest` |
| Registry refused to start | `set_camera` declared with no server handler | `DEVICE_TOOLS` subtracted, mirror guard added | 2 construction guards |
| Avatar mouth a device buffer early | RMS taken at write time | frame-indexed against `getPlaybackHeadPosition()` | `OutputLevelMeterTest` asserts alignment |

A further class of bug worth one line in the article: **`@lru_cache` on the
system-instruction loader** meant a running server kept serving the old prompt
after an edit, twice costing debugging time. The backend is now run with
`--reload` during development.

---

## 5. What worked — tested end to end

"Tested end to end" below means a real Gemini Live session, not a mock.

| Capability | Status | Evidence |
|---|---|---|
| Bidirectional voice conversation | **Works on device** | repeated sessions on a Galaxy A35; backend log `live.session_issued` ×6 on 2026-10-09 |
| Assistant speaks first, names the spot and the time | **Works** | `make verify-opening`, both languages (below) |
| Inland greeting variant | **Works** | `make verify-opening`, both languages (below) |
| `find_place` for any named spot | **Works** | live Q&A; ambiguity path returns options |
| `get_current_conditions` with real marine data | **Works** | tool executions in backend log |
| Regulations with citation | **Works** | live Q&A (below) |
| Camera opened by the model (`set_camera`) | **Implemented; not verified by voice on device** | 7 controller tests; no on-device transcript captured |
| Avatar mouth tracks the voice | **Works on device** | user-confirmed after the §3.2 fix |
| Session creation / catch logging / memory | **Implemented; backend-tested** | tool tests; not exercised end to end by voice in this evidence set |
| Barge-in | **Implemented** | `EchoGate` tests; not separately measured on device |

Verbatim from `make verify-opening` (real service, tool calls answered from the
real Nominatim and Open-Meteo):

```
[tr] “Bostanlı’da saat sekiz buçuk geçmiş, deniz çarşaf gibi.
      Suyu bir göster, ne atardım söyleyeyim.”
[en] “Bostanlı — just gone quarter to nine. It's dead calm out here with
      barely any wind, so the surface is like glass. Show me the water
      and I'll tell you what I'd throw.”

[tr inland] “Merhaba Deniz. Bornova'dasın, denizden epey uzaktasın.
             Hava açık, rüzgâr sakin; nerede avlanmayı düşünüyorsun?”
```

Regulations, real Live session, tool answered from the real service:

```
Q: Çipura kaç santimden küçükse bırakmam gerekiyor?
A: "...çipura için asgari avlanma boyu yirmi santimdir. Kuralların
    değişebileceğini unutmamak gerekir."
Q: Akya için boy sınırı ne?
A: "Akya için asgari boy sınırı kırk santimetre..."         (correct fish)
Q: İskorpit kaç cm olmalı?
A: "Bunu doğrulanmış bir kaynaktan teyit edemiyorum."       (correct refusal)
```

---

## 6. What failed or remains uncertain

- **Google Search grounding is unusable on this key.** `429
  RESOURCE_EXHAUSTED`. `GEMINI_ENABLE_GOOGLE_SEARCH` defaults **false**,
  because without its own quota the whole Live session fails at setup with
  1011.
- **Subtitles can translate a language into itself.** The subtitle target is
  fixed at session start as "the opposite of the account language", but the
  conversation language can drift when the angler speaks the other language.
  Observed on device: Turkish speech subtitled with a Turkish paraphrase. The
  translate endpoint sends `source_language: "auto"` but does **not** return
  the detected language, so the client cannot notice. **Open.**
- **`set_camera` has not been confirmed by voice on device.** The code path is
  tested; no on-device transcript of "do you see this?" exists in this evidence
  set.
- **1011 "Internal error" is a flaky server response** on the turn after a tool
  response — observed on roughly half of runs, independent of inputs.
  Production handles it by reconnecting with the resumption handle. Frequency
  was characterised during development but **no systematic trial count was
  recorded**.
- **Regulation coverage is partial.** 9 species of the circular's much longer
  Çizelge 7. Unlisted species still refuse. Inland species and regional closed
  seasons are not transcribed at all.
- **The avatar refactor is unverified by test.** The face is now drawn by one
  shared painter used by Fishing Mode and Home; equivalence with the previous
  drawing was checked by reading and on-device inspection, not asserted. There
  is no Compose UI test infrastructure in use (`app/src/androidTest/java` is
  empty although the dependencies are wired).
- **Non-blocking (`NON_BLOCKING`) function calling is off by default**: the
  tool-use documentation confirms only that Gemini 3.1 Flash Live lacks it and
  does not confirm support for `gemini-3.8-live` (`config.py`).

---

## 7. Testing evidence

All figures below were produced by running the suites on 2026-10-09.

| Suite | Result | Command |
|---|---|---|
| Backend | **297 passed**, 0 failed | `make test-backend` |
| Backend lint (`ruff`) | clean | `make lint` |
| Backend format | 62 files already formatted | `make fmt-check` |
| Backend types (`mypy`) | **no issues in 45 source files** | `make typecheck` |
| Android unit | **129 passed**, 0 failed, 0 skipped | `./gradlew testDebugUnitTest` |
| Android lint | clean | `./gradlew lintDebug` |
| Android build | debug APK builds | `./gradlew assembleDebug` |
| Web unit | **35 passed, 1 skipped** (4 files passed, 1 skipped) | `make test-web` |
| Web typecheck + production build | clean, built in 2.14 s | `make web-build` |

**Kinds of test, kept distinct:**

- **Automated, never contacting Google.** All of the above. The backend suite
  pins wire shape (`TestWireSetupShape`), model compliance
  (`TestGemini38LiveCompliance`), the no-fabrication guarantee
  (`tests/test_regulations.py`) and geocoding ranking
  (`tests/test_geocoding.py`). Android pins echo-gate thresholds, avatar/audio
  frame alignment, the tool loop, the session opening and the wire frame.
- **Mocked.** Every Android client test runs against `MockLiveAiSession`; every
  test double lives there by design.
- **Against the real service, operator-run.** `make verify-live`,
  `make verify-live-web`, `make verify-opening`. The first two were **not run
  while compiling this report**; `make verify-opening` was, and passed all
  checks in both languages.
- **The one skipped web test is skipped by design**:
  `describe.skipIf(!ENABLED)` in `web/src/live/verify-live.test.ts:46`, enabled
  only by `FISHMATE_VERIFY_LIVE=1`.
- **Emulator.** Not used for Live verification. All on-device evidence here
  comes from a physical Galaxy A35 (`R6CX400YLVZ`).

---

## 8. Measurements available and missing

### Measured

**Backend HTTP latency**, from the development server's structured log
(`http.request duration_ms`), same machine, `adb reverse` tunnel:

| Endpoint | n | min | median | max |
|---|---|---|---|---|
| `POST /api/v1/live/session` (token mint) | 6 | 461 ms | **862 ms** | 1129 ms |
| `POST /api/v1/tools/execute-batch` | 16 | 9 ms | **136 ms** | 738 ms |
| `POST /api/v1/live/translate` | 79 | 484 ms | **830 ms** | 12 266 ms |
| `GET /api/v1/conditions` | 9 | 3 ms | **394 ms** | 1462 ms |

**Tool execution** (server-side only, excludes network), from `tools.executed
duration_ms`: `get_current_conditions` 316 ms cold / 0 ms cached,
`get_tackle_inventory` 0–2 ms, `recommend_setup` 5 ms, `find_place` 503 ms
(Nominatim round trip).

**Audio generation vs playback** (real service, one three-sentence answer):
16.48 s of speech delivered in 5.02 s; `generationComplete` at 5.04 s; 12.65 s
unplayed at that moment. → **≈3.3× real time**.

**Marine grid resolution**: cells ≈ 0.0833° ≈ 9 km; sea state still reported
10 km inland on one bearing and 25 km on another from the same origin.

**Echo without AEC**: 32 interruptions in 50 s on a Galaxy A35.

**Geocoding ranking**: correct Bostanlı ranked 10th of 12 by Nominatim
importance; `SEARCH_LIMIT = 12` exists because of it.

**Configured** (not measured, but exact): camera 0.5 fps, 768 px max dimension,
JPEG quality 70; compression trigger 16 000 tokens → 8 000; VAD silence 550 ms,
prefix padding 300 ms.

### Not measured

- **Time to first audio token** from the user finishing speaking — the
  perceptual latency that matters most. **Not measured.**
- End-to-end round trip for a spoken question answered via a tool call.
- Reconnection time after a dropped socket, and how often reconnection happens
  in a real session.
- Audio and video **bandwidth** in bytes/second over a session.
- **Token usage and cost** per session. `usageMetadata` is decoded
  (`ProtocolTest` covers `totalTokenCount`) but no session totals were
  recorded.
- **Battery consumption.** Not measured, despite being the stated reason for
  several design choices (0.5 fps, `MEDIA_RESOLUTION_LOW`).
- Session ceilings (15 min audio / 2 min audio+video) are **documented service
  limits, not independently measured here**.
- The 1011 flake rate ("roughly half of runs") is an impression from
  development, **not a counted trial**.

---

## 9. Lessons for developers using Gemini Live API

1. **Assert the bytes, not the object.** The single worst bug in this project
   was a correct in-memory object that serialised to the wrong JSON. Any
   serialiser with "omit defaults" semantics can silently delete a protocol
   field whose default happens to match.
2. **`generationComplete` is not "finished speaking".** Drive UI state from
   playback position, and size playback buffers for a whole turn delivered at
   several times real time.
3. **Three documented settings did not behave as documented.** Verify each one
   against the live service; `enable_affective_dialog` is accepted at setup and
   kills the first turn.
4. **The wire `setup` is not the SDK's shape.** Let the SDK's own converter
   produce it.
5. **`receive()` is per turn.** A tool call ends the turn; the spoken answer
   needs a fresh iterator.
6. **On a loudspeaker, suspect echo before you suspect the prompt.** Echo makes
   the model switch languages, which reads like a prompt bug.
7. **Attach the audio effects explicitly.** `VOICE_COMMUNICATION` does not
   guarantee `AcousticEchoCanceler` is attached.
8. **Grounding needs its own quota**, and without it an entire Live session can
   fail at setup with 1011.
9. **Measure the vendor's data before building on it.** The same vendor's
   geocoder and marine grid were both wrong for this use case in ways that a
   few HTTP requests exposed immediately.
10. **Design the refusal, then make sure it is not the only path.** A default
    of "I cannot confirm this" is correct for legal data and useless if no
    verified source is ever loaded.

---

## 10. Suggested article narrative

Open on the shoreline failure, not the architecture: the assistant that
answered every regulation question with "check the ministry circular", the one
that would not say hello, the mouth that stopped moving while the voice kept
going. Each has a clean, surprising root cause and a measurement behind it.

A workable spine:

1. **The premise** — Gemini Live as the interaction layer, not a feature. Why a
   fishing boat is a good adversary: wind, a loudspeaker, gloves, one hand.
2. **"It never said hello."** The `turnComplete` serialisation bug. Build the
   suspense properly: successful send, healthy socket, silent model.
3. **"It stopped moving its mouth."** The 3.3× measurement, and the second bug
   it exposed — speech silently truncated by an 8-second buffer.
4. **"It kept interrupting itself."** 32 interruptions in 50 s, and the
   language-switching symptom that pointed at the prompt instead of the echo.
5. **"It couldn't find Bostanlı."** Vendor geocoder wrong, Nominatim ranked it
   tenth, and the ranking had to become ours.
6. **"It refused to answer anything legal."** The safe default that made the
   feature a no-op, and the akya/sarıkuyruk collision that would have cost a
   fine.
7. **What this teaches** — §9, condensed.
8. **What is still unknown** — §8's "not measured" list, stated plainly. It
   will read as credibility, not weakness.

---

## 11. Claims that are safe to publish

- Model `gemini-3.8-live` over the constrained Live endpoint with ephemeral
  tokens minted server-side; media direct to Google, never proxied.
- Real voice sessions have run on a physical Galaxy A35.
- `proactivity` is rejected at setup; `enable_affective_dialog` is accepted at
  setup and fails the first generation turn; `thinkingLevel` must be omitted.
- The wire `setup` has no `config` wrapper.
- `session.receive()` is per turn and ends at a tool call.
- 16 kHz in, 24 kHz out.
- One measured turn delivered 16.48 s of audio in 5.02 s, with
  `generationComplete` 12.65 s before playback would end.
- 32 interruptions in 50 s on a loudspeaker without explicit AEC.
- Open-Meteo geocoding returned five inland villages for "Bostanlı" and nothing
  for "Kilyos"; Nominatim ranked the correct Bostanlı tenth of twelve.
- The marine grid reported sea state 25 km inland; spacing ≈ 9 km.
- Google Search grounding returned `429 RESOURCE_EXHAUSTED`.
- Test counts in §7, each with the command that produced it.
- The `turnComplete` serialisation bug, with the before/after JSON.
- The akya / sarıkuyruk species collision and its 10 cm consequence.

## 12. Claims that should NOT be made without further testing

- **Any latency claim about the conversation itself.** "Sub-second response"
  or similar is unmeasured. Only backend HTTP latency was measured, on
  localhost.
- **Any cost or token-usage figure.** Nothing was recorded.
- **Any battery claim.** Not measured, despite driving design decisions.
- **"Works offline."** Reads come from Room and writes queue in `pending_ops`,
  but no offline session was exercised in this evidence set.
- **"Hands-free for a three-hour session."** Session ceilings and reconnection
  are implemented; no long-run test was performed.
- **"The model reliably opens the camera when asked."** `set_camera` is tested
  in code, not confirmed by voice on device.
- **"Barge-in works reliably in wind."** Implemented and unit-tested; not field
  tested.
- **Any statement that subtitles are correct.** A known open defect translates
  a language into itself.
- **"Production ready" / "deployed".** The backend is deployed at
  `fisherman.salginci.com`, but all evidence in this report comes from a local
  backend over an `adb reverse` tunnel.
- **A 1011 flake rate.** "About half" is an impression, not a counted trial.
- **Regulation coverage.** 9 species, one table, one country. Do not imply
  general legal coverage.

---

## Additional experiments needed before publication

1. **Time to first audio** after the user stops speaking, over ≥20 turns,
   median and p95. This is the number readers will look for.
2. **Token usage and cost per session** from `usageMetadata`, for a typical
   10-minute audio session and a 2-minute audio+video session.
3. **Battery drain** over a 60-minute session, camera on and off — it justifies
   the 0.5 fps and `MEDIA_RESOLUTION_LOW` choices.
4. **1011 flake rate** as a counted trial: N tool-call turns, failures observed.
5. **Reconnection**: forced socket drops, time to resume, and whether the
   conversation survives intact.
6. **Bandwidth** in bytes/second, audio-only and audio+video.
7. **An on-device `set_camera` transcript** so the hands-free camera claim can
   be made.
8. **A field session** — actual shoreline, wind, loudspeaker — with logcat
   captured. Every echo-related finding so far comes from one device in one
   environment.
