# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

FishMate AI — a hands-free fishing companion where **Gemini Live is the
interaction layer**, not a bolted-on feature. Turkish-first. Built as a public
Google Developer Expert showcase, so correctness of the Gemini integration
outranks new features.

Three deliverables: a FastAPI backend (`backend/`), a Compose Android app
(`android/`), and a Vite/React web app (`web/`). Deployed at
**https://fisherman.salginci.com** (host `tr-vds`); the web app runs locally
only so far.

`web/` covers Fishing Mode plus sign-in and home; tackle, catch log and
settings remain Android-only. It deliberately mirrors the Android module names
(`LiveSession`, `FishingModeController`, `AudioCapture`, `EchoGate`,
`CameraSource`) so the two clients can be read against each other — a fix to
the Live layer on one side usually belongs on the other, and `web/README.md`
lists the browser-specific traps.

**Not every web fix ports.** The adaptive jitter buffer in
`web/src/audio/AudioPlayer.ts` answers a Web Audio problem: there we schedule
chunks by hand on the audio clock, and the Live API delivers them at exactly
real time, so there is no slack to absorb a late chunk. Android's `AudioTrack`
runs in stream mode with a blocking write and the OS buffer absorbs the same
jitter, so there is nothing to port. Conversely the avatar
(`ui/screens/fishing/AssistantAvatar.kt` ↔ `web/src/ui/Avatar.tsx`) did port,
but its level measurement could not: `AnalyserNode` has no Android equivalent,
so `audio/OutputLevelMeter.kt` files each window under the frame position it
will *play* at and reads it back against `AudioTrack.getPlaybackHeadPosition()`.
Measuring loudness when a chunk is written instead moves the mouth a device
buffer early — it looks like bad dubbing, not like a bug, which is why
`OutputLevelMeterTest` asserts the alignment directly.

The face itself is drawn in one place on Android, `ui/components/AssistantFace.kt`,
because Home's primary action is the avatar too: you start a session by
tapping the thing you are about to talk to. Only the geometry is shared — the
*motion* is each caller's own, and deliberately so. Fishing Mode drives the
mouth from the playback waveform in a `withFrameNanos` loop, which earns its
frame budget on something that changes every frame; Home has no audio, so it
uses an infinite transition for a slow breath, adds an outward wave only while
a session is already running (the state the old button spelled out by swapping
its icon), and keeps the mouth shut, since a mouth moving with no voice behind
it is the avatar miming. Home keeps the text label under the face: a bare face
is a guessing game for a first-time user, and both strings were already
translated.

Two more things differ by platform rather than by accident:

- **Voice selection.** The web app picks a voice per session on Home; Android
  stores it as a preference in Settings. Neither passes it down from the
  client, because `POST /api/v1/live/session` already falls back to
  `user.preferences.voice_name` when the request omits one. The 12 names in
  `live/LiveVoices.kt` and `web/src/ui/HomeScreen.tsx` were each verified to
  open a real session — an unsupported voice fails the whole connection at
  setup rather than degrading, so do not add one from the docs alone.
- **The repeat counter** (`RepeatCounter`, both clients) is the measurement of
  the echo loop: the model hears itself, VAD calls it a barge-in, and it
  restarts the same sentence. It is cumulative per session and incremental by
  necessity — both clients run their event loop on the UI thread next to a
  60 fps avatar, so rescanning the whole transcript per fragment would spend
  that thread's budget hunting stutter and cause stutter doing it. The web app
  shows it in its counter strip; Android shows it only in debug builds, since
  the Fishing Mode screen belongs to someone holding a rod.

## Commands

```bash
make check                 # backend gate: ruff + format + mypy + 246 tests
make test-backend          # pytest only
make cov                   # with coverage
make lint / fmt / typecheck
make db-up && make migrate # PostgreSQL on :55432, then Alembic
make run                   # backend on :8099

make test-android          # Android unit tests
make android-build         # debug APK
cd android && ./gradlew lintDebug

make web-install && make web   # web app on :5178, proxying /api to :8099
make test-web                  # web unit tests
make web-build                 # type-check + production build

make verify-live           # REAL Gemini Live session — needs GEMINI_API_KEY
make verify-live-web       # the same, driven by the web client's own code
make verify-opening        # REAL session: does it speak first and name the spot?
./deploy/deploy.sh         # deploy to tr-vds (idempotent)
```

Single test:
```bash
cd backend && .venv/bin/python -m pytest tests/test_live_tokens.py::TestGemini38LiveCompliance -q
cd android && ./gradlew testDebugUnitTest --tests '*LiveSessionTest*'
```

Ports 55432/8099/5178 are deliberate — 5432 and 8080 collide with other local
work on this machine (8080 is held by another Docker stack).

The web app needs a **secure context** for `getUserMedia`: `localhost` counts,
a LAN IP does not, so testing from a phone needs a tunnel rather than
`vite --host`.

## Architecture: the parts that span files

**Media goes straight to Google; data does not.** The Android app opens its own
WebSocket to Gemini Live. The backend never proxies audio or video — it mints a
short-lived *constrained* ephemeral token and executes tool calls, with one
deliberate exception below.

```
Android ──POST /api/v1/live/session──▶ backend ──auth_tokens.create──▶ Gemini
Android ──wss://…BidiGenerateContentConstrained?access_token=…──────▶ Gemini
Gemini  ──toolCall──▶ Android ──POST /tools/execute-batch──▶ backend ──▶ DB/APIs
```

**One tool runs on the device, not the server.** `set_camera` lets the model
turn the angler's camera on when they say "do you see this?", so nobody has to
press a button mid-cast. It is *declared* in
`backend/app/tools/declarations.py` — the declaration has to be minted with the
session token, since that is what the model sees — but it is listed in
`DEVICE_TOOLS` and executed by whichever client owns the hardware. Three
guards keep that split honest: the registry refuses to start if a non-device
tool lacks a handler *or* if a device tool has one, and
`/tools/execute-batch` answers a device tool with `device_tool` rather than
"unknown tool", so a client that forgot to intercept it says so instead of
sending whoever debugs it to the wrong file.

The tool returns only **after** a frame has actually been sent, and a camera
that will not start comes back as an error with
`instruction_to_model` telling the model not to describe anything. A model told
"camera on" that then sees nothing will describe a fish that is not there,
which is the one failure this product cannot have.

**The session opens knowing where and when it is.** The client sends its
position with the token request; the backend coarsens it to the consented
precision (`core/location.py` — the one place that rule is written), reverse
geocodes it and puts a place name in `user_brief`. The client adds the device
clock and primes two turns: the facts, then a directive sent with
`requestReply=true` so the assistant **speaks first**. Without that the model
opened by asking where the angler was fishing, which the phone already knew.

Unit tests can pin the plumbing but not the behaviour, so `make verify-opening`
runs the real thing: it sends the exact two turns
`primeFishingContext` builds, answers tool calls from the real Nominatim and
Open-Meteo, and asserts the assistant spoke first, named the spot, called
`get_current_conditions` and never asked where the angler was. It caught three
things no unit test could. The English session looked its *own* spot up with
`find_place` before answering — correct, but a wasted round trip on the one
turn where the wait is most audible, so both instruction files now say to call
the conditions tools with no location argument for where the angler already is.
And the check for the second spot cannot be a phrase match: whether reporting
conditions or asking which one is right depends on what geocoding returned, so
the expectation is taken from the resolution, not from the model's wording.
Third, it caught an English session opening in Turkish — the English
instruction's one blockquoted example was Turkish, and a sample reply outweighs
every rule in the file about which language to use.

**The opening does not propose the next move.** It used to end by asking to see
the water, in the directive and in both instruction files, and that is wrong in
half of all sessions: the same angler opens this app at the kitchen table to
plan tomorrow, where there is no water to show and what they want is a list of
what to take and what they are missing. So the opening proves it knows the
place, the hour and the conditions, and then stops — their first sentence says
which situation it is, for free. Inland is the one exception, and it is not a
proposal: with no water near them there is nothing to say about conditions
where they stand, so asking where they are headed is what makes the rest
possible.

**Place names are geocoded, and the ranking is ours.** `find_place` exists
because "how's the weather at Bostanlı?" used to be answered with "I can't
check that". Two measured facts shaped `services/geocoding.py`:
Open-Meteo's geocoder — the obvious choice, same vendor as the weather — is
wrong for these users (it returned five inland villages for "Bostanlı" and
nothing at all for "Kilyos"); and Nominatim finds them but **ranks them
badly**: the İzmir Bostanlı came back last of twelve, behind villages
300-1400 km away. So candidates are re-sorted by distance from the angler,
and a genuine tie returns options for the model to ask about rather than a
guess. Reporting a sea state for a landlocked namesake is the failure being
designed against.

**Four abstractions carry the design** (`docs/ARCHITECTURE.md`):
`LiveAiSession` (+ `MockLiveAiSession` — every test double lives here),
`CameraSource`, `FishingToolRegistry`, `WakeWordEngine`.

`FishingModeController` owns mic, camera, tool loop and reconnection, and
reduces all of it to one `FishingModeState`. The UI knows nothing about
WebSockets; the domain knows nothing about Gemini.

**Errors never surface as exceptions.** `apiCall()` (Android) and
`FishMateError` (backend) convert everything into a user-presentable
`{code, message}`. That is what makes "no stack traces in the UI" structural.

**The tool path is the critical path of the first sentence.** The model cannot
speak until a tool call is answered, so anything slow between `toolCall` and
the response is silence the angler hears. `FusedLocationProvider.lastKnown()`
asked for a brand new fix on every call with no timeout, and a device log
measured 17.4 s before the token request and 11.6 s before
`get_current_conditions` ran — long enough for the greeting watchdog in
`FishingModeController` to fire twice and open the mic on a model that had
never been given what it needed to talk. It now serves its own recent answer,
then the platform's remembered fix, and only then asks for a new one under a
deadline. Staleness is free here: coordinates are coarsened to ~1 km and sea
state comes off a ~9 km grid. Treat a watchdog firing as a symptom and look
for what is holding the tool loop.

**Offline-first:** reads come from Room, writes go to Room then the network,
and failed writes queue in `pending_ops` for `SyncManager` (drains oldest-first,
stops at first failure so ordering holds).

## Gemini Live: facts established by running it, not reading docs

`docs/GEMINI_LIVE_IMPLEMENTATION.md` is authoritative. The non-obvious ones:

- **The wire `setup` has no `config` wrapper.** That is an SDK abstraction;
  sending it fails with `Unknown name "config" at 'setup'`. `model` needs the
  `models/` prefix, and `responseModalities`/`speechConfig`/`mediaResolution`
  live under `generationConfig`. `build_wire_setup()` delegates to the SDK's
  own `_LiveConnectParameters_to_mldev` converter — **do not hand-roll this.**
- **`session.receive()` is a per-turn generator.** It ends at `turnComplete`.
  The model ends its turn when it issues a tool call, so the spoken answer needs
  a *fresh* `receive()`. Holding one iterator silently yields zero audio.
- **`proactivity` is rejected** at setup (1007) despite being documented.
- **`enable_affective_dialog` is accepted at setup then kills the first
  generation turn** (1007). Worst kind of failure — looks configured.
- **`thinkingLevel` must be omitted** for `gemini-3.8-live`; it is also in
  `LOCKED_ADDITIONAL_FIELDS` so a tampered client cannot add it.
- **`googleSearch` needs its own quota.** Without it the whole session fails at
  setup with 1011, so `GEMINI_ENABLE_GOOGLE_SEARCH` defaults **false**.
- **1011 "Internal error" is a flaky server response** on the turn after a tool
  response (~50%). Production handles it by reconnecting with the resumption
  handle; don't treat it as a client bug.
- Audio: **16 kHz in, 24 kHz out** — different rates, both from the server.
  `AudioCapture` must use `VOICE_COMMUNICATION` (not `MIC`) or the model hears
  itself and interrupts continuously.
- **`MODE_IN_COMMUNICATION` buys echo cancellation by making the handset
  half-duplex, which is worse.** The platform AEC lives in the HAL's voice
  path, and that path is only entered when the device mode says so —
  `VOICE_COMMUNICATION` capture plus `USAGE_VOICE_COMMUNICATION` playback does
  not enter it, which is why `AcousticEchoCanceler.enabled` has always reported
  `true` over a canceller that was never running. Entering the mode does arm
  it, and it works: measured on a Galaxy A35, the loudspeaker leak went from a
  peak of **5943 to a flat 0**. But the same vendor stack suppresses the *near*
  end for as long as the far end is playing, so the microphone reads 0 for the
  whole of the assistant's turn and a question asked over it never reaches the
  gate, the server, or anything else. Measured both ways in one sitting:

  | device mode | mic while the assistant speaks | leak | barge-in |
  | --- | --- | --- | --- |
  | `MODE_NORMAL` + manual AEC/NS | live | 175–5943 | possible, needs the gate |
  | `MODE_IN_COMMUNICATION` | dead (0) | 0 | impossible |

  Neither the capture source (`MIC` and `VOICE_RECOGNITION` were both tried)
  nor the input device (the HAL picks `IN_BACK_MIC`; forcing
  `TYPE_BUILTIN_MIC` with `setPreferredDevice` takes, and changes nothing)
  moves this. A third finding came free: in communication mode, attaching
  AEC/NS *ourselves* on top of the vendor stack zeroes the stream outright,
  even in silence — the manual attach is a `MODE_NORMAL` workaround and is
  actively harmful anywhere else. So this handset cannot give us full-duplex
  vendor cancellation, and an echo canceller we want to rely on has to be ours,
  fed by the far-end signal we already hold. Do not re-enter communication mode
  expecting the leak measurement alone to settle it.

- **On a loudspeaker, `VOICE_COMMUNICATION` alone is not enough.** The model's
  own voice reaches the mic, VAD calls it a barge-in, and the conversation
  collapses — 32 interruptions in 50 s on a Galaxy A35. It also makes the model
  **switch to languages nobody spoke**, because it language-detects its own
  distorted output; suspect echo before suspecting the prompt. Fixed by
  attaching `AcousticEchoCanceler`/`NoiseSuppressor` explicitly (they are often
  *not* attached despite the audio source) plus `audio/EchoGate.kt`, which
  gates the mic against a measured echo floor on speaker only. Do not "fix" it
  by muting during playback — that removes barge-in. Thresholds are pinned by
  `EchoGateTest`; two plausible-looking estimators both failed on-device first.
- **Barge-in on a loudspeaker is solved by ducking, not by a threshold.**
  Three settings of the gate were measured on a Galaxy A35 and each one either
  cut the assistant off mid-greeting or swallowed a question asked over it:
  the leak read 1888 against a predicted 818 early in a turn, while a real
  question read 5585 against a predicted 470 and was rejected by a bar carrying
  the loudest moment of that turn. The ratios overlap, so no bar separates them.
  So on the first hint of speech — `EchoGate.isSuspectingSpeech`, which is
  deliberately weak evidence — the controller drops the output to
  `AudioPlayer.DUCK_FACTOR` for 600 ms. That changes what is in the air instead
  of what we believe about it: the leak falls with the volume, the angler does
  not, and the unchanged accept test finally has a margin. A false duck costs a
  dip in volume; a missed question costs the conversation, which is the
  asymmetry that licenses acting early. The duck is passed to the gate
  explicitly as `outputScale`, never inferred from the reference — a reference
  cannot tell a ducked speaker from a quiet passage, and inferring it would let
  the leak in the gaps between words through. Nothing is learned while ducked.
  Not yet ported to `web/`.
  The gate reads two different references and the distinction is load-bearing:
  the *bar* is built on the held envelope, because a gap between words is not
  silence, while *evidence* about the leak may only be taken at an instant the
  speaker is honestly loud. Learning in the gaps is where the angler's own
  voice gets counted as echo, and three separate measurements show the bar then
  climbing above the person it exists to hear.
- **One `AudioCapture` is shared for the process, and so is the gate inside
  it.** A capture loop that outlives its collector therefore does more than
  waste the microphone: a second session opens its own `AudioRecord` on top and
  both feed one `EchoGate`, whose calibration, room floor and per-turn decay
  are all counted per frame and so all run at double speed against two
  signals. It happened, and the tell was the gate's own diagnostics printing
  twice a second. The loop never suspends, so cancellation cannot interrupt it
  — it has to test `isActive` itself, which is why it also carries a
  generation.
- Session ceilings: 15 min audio, **2 min audio+video**, ~10 min per
  connection. Compression + resumption are mandatory, not optimisations.

Tests that pin all of this: `tests/test_live_tokens.py::TestWireSetupShape`,
`::TestGemini38LiveCompliance`. **No automated test contacts Google** — use
`make verify-live` for that.

## Conventions worth keeping

- **Never fabricate data for the model.** Tool payloads carry
  `instruction_to_model` telling it what *not* to assert. Regulations default to
  `verified=false` and refuse; a wrong size limit is a fine for the user.
  `resources/regulations/tr.json` used to ship empty for that reason, which
  also meant the feature never worked — asked about çipura the assistant could
  only recite "check with the ministry". It now carries Çizelge 7 of circular
  2024/21 (1/9/2024–31/8/2028), transcribed from the Resmî Gazete text with a
  `source_url` and `effective_from` on **every** entry, which is the invariant
  the tests enforce now that "the file is empty" is no longer one. A species
  the circular does not list stays absent here and still refuses: the table has
  Lipsöz (*Scorpaena scrofa*) at 15 cm but no İskorpit (*S. porcus*), and
  lending one fish's limit to another is the exact failure being designed out.
  Entries are keyed by scientific species key for the same reason — the
  catalogue called *Seriola dumerili* "Akya", which is a different fish that
  the circular sets 10 cm higher, so a common-name match would have told an
  angler an undersized akya was legal.
- **The grounded fallback is not a safety net.** It asks Gemini with Google
  Search grounding, and that needs its own quota: in the field it returned
  `429 RESOURCE_EXHAUSTED` and every regulation question degraded to a refusal.
  Hence the curated file. A lookup that *fails* is now distinct from a rule
  that does not exist (`lookup_unavailable`), because "I can't check right now"
  invites trying again and "there is no verified rule" teaches the angler the
  feature is broken.
- **AI output is never immutable.** `ai_confidence` + `species_confirmed_by_user`
  on every catch; correcting the species clears the confidence.
- **Recommend only gear the user owns.** An empty inventory returns an
  instruction to *ask*, not to invent. There used to be a way around that and
  it had to go: onboarding offered starter kits — LRF, light spin, surf — and
  one tap wrote a dozen named items, minnow included, into the angler's
  inventory. From then on `recommend_setup` read a plausible inventory of gear
  he had never owned and told him to fish a minnow he did not have. A
  believable inventory is worse than an empty one, because the empty one makes
  the assistant ask. `add_tackle` is the other half of asking: the question was
  already being asked, but the answer had nowhere to go, so the next session
  asked again. It writes with `ai_identified`, which the Tackle screen already
  renders as "confirm this" and any edit clears — so a misheard item is a row
  the angler can fix, never an assumption presented as their own gear.
- **Advice starts from the fish, not the gear** (`# ORDER OF ADVICE` in both
  instruction files). Which species is realistic here and now, then the ways to
  catch it as options — bait *and* artificial, named as types so the angler can
  answer "got that" or "haven't" — then their choice, and only then a specific
  item, which may come from the inventory tools alone. Naming the lure first
  and working backwards to a fish is the failure the user reported from a
  shore: "kafasına göre yönlendirmemeli".
- Tool argument names stay `snake_case` — they are matched against
  `app/tools/schemas.py` on the way back. The wire converter must not touch
  `parameters.properties`.
- Coordinates are coarsened to ~1 km **at write time** when consent is
  "approximate", and `Locale.ROOT` is used for coordinate formatting (Turkish
  locale otherwise renders `38,46,27,09`).
- `UtcDateTime` everywhere — Postgres returns aware datetimes, SQLite naive.
- Android: `android.newDsl=false` is required (AGP 9's DSL is incompatible with
  `kotlin-android`, and KSP isn't wired for built-in Kotlin). Removed in AGP 10.

## Deployment

`tr-vds` runs ~28 other containers behind a shared `nginx-proxy` +
`acme-companion`. **Integrate, don't reconfigure**: join the external
`cicekci-proxy` network and set `VIRTUAL_HOST` / `LETSENCRYPT_HOST`. Publish no
host ports. Three containers (`lms-n-manager`, `notification-consumer`,
`deployguard`) have been crash-looping for ~9500 restarts — pre-existing, not
ours.

Production refuses to boot with a wildcard CORS, a missing `AUTH_SECRET`, or
demo mode on; `/docs` and the demo router are absent there by design.
