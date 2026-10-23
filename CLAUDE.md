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

**Cancellation is not an error, and "offline" is not a synonym for "failed".**
Both halves of that were wrong at once, and together they put "You're offline"
on a phone with four bars. A token the backend no longer recognises is cleared
by `UnauthorizedInterceptor`, which tears down the scope every in-flight call
was launched in; those calls then arrived in `apiCall` as
`IOException: Canceled` and `JobCancellationException`, were mapped to
`AppError.offline()` and `unknown()`, and Home's `SyncBanner` -- which took
*any* conditions error as proof of being offline -- said so. `apiCall` now
rethrows both shapes of cancellation, which is also what structured
concurrency requires, `RetryInterceptor` does not retry a cancelled call, and
the banner claims `offline` only for `CODE_OFFLINE`; every other reason is
reported by the conditions card in the backend's own words. An angler told
they are offline goes looking for signal, which is the one thing that was
never wrong.

**A lost SYN is not a slow server, and the connect timeout is what tells them
apart.** Measured against `tr-vds` during an episode on 23/10/2026: about one
TCP connection in ten got no SYN-ACK, on port 443 and on plain SSH alike, with
the recovering connects landing at 1.1-3.5 s -- the kernel's SYN retransmit
schedule. Nothing we run was involved: the container was healthy, nginx-proxy
had logged two listen drops in eight days, conntrack was at 332 of 262144, and
`ListenOverflows` was zero. The episode cleared by itself within ten minutes,
so expect it to come back; it is the host's network path. What it cost was set
by our own timeout: a sign-in took 31 s to fail, because OkHttp spent fifteen
seconds on a dead SYN, retried, and spent fifteen more. The connect timeout is
now 5 s, which keeps every retransmit that recovers and abandons the rest
while a retry is still cheap. The read timeout stays at 30 s, because a slow
answer is still an answer.

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
- **Barge-in is the server's decision, and for a long time it was never
  given the chance to make it.** The Live API runs its own voice activity
  detection and cancels the turn the moment it hears speech — the backend sets
  `activityHandling: START_OF_ACTIVITY_INTERRUPTS` explicitly — and none of
  that can fire on audio it never receives. The gate withheld the microphone
  for the whole of the assistant's turn, so the one component in this system
  actually built to recognise speech was never shown any, and we were
  reimplementing it locally with an RMS threshold. That is the real reason
  every threshold here failed. The microphone now streams through the
  assistant's turn and the gate only decides when to stop playback early, which
  is a latency optimisation. What makes that safe is the pair below: the
  canceller attenuates the assistant's own voice in what we send, and
  `gemini_vad_start_sensitivity` is **LOW** rather than the default HIGH, so
  the residue falls under the detector while the angler — whose voice nothing
  attenuates — stays over it. Measured on a Galaxy A35 with nobody in the room
  talking: 41 self-interruptions in 40 s at the default sensitivity, 2 at LOW.
  END sensitivity stays HIGH, because a false end costs a moment and a false
  start costs the sentence.

- **The echo canceller is SpeexDSP, vendored and built with the NDK**
  (`app/src/main/cpp`, `FarEndRing`, `SpeexEchoCanceller`). A hand-rolled NLMS
  came first and reached 89 % cancellation against a synthetic room while
  refusing to converge on a handset; cancelling echo robustly is a project in
  itself, so the subtraction belongs to a library and the alignment, the
  far-end ring and the meaning of the residual stay in Kotlin where they can be
  read and tested. Two bugs found on the way are worth more than the filter:
  far-end audio was filed **after** `AudioTrack.write`, which blocks until the
  device has taken the samples, so the ring ran 120 ms behind the playback head
  and the canceller was handed silence for exactly the frames carrying the echo
  (`far=0` against a microphone reading 2873) — the level meter had the same
  lateness, which is what made it report a silent speaker over a loud room. And
  the reference has to *lead* the microphone: a recorder hands over a buffer
  that has been filling for up to 80 ms, so reading the far end at the head
  puts it behind the sound it is meant to explain, which no canceller can
  represent. Cancellation now measures 0.25-0.90 frame to frame on a Galaxy
  A35; the residual is only judged in place of the microphone once the smoothed
  figure clears `TRUSTED_REMOVAL`, because a half-cancelled frame judged against
  the room floor is worse than no cancellation at all.

- **The echo gate's hardest problem was a broken reference, not a bad
  threshold.** The gate explains the microphone by what the speaker is
  playing, so everything rests on that reference being right, and it was wrong
  in two ways at once. It reported the level of the frame *currently* leaving
  the speaker, while the microphone hears what left it a hundred milliseconds
  ago; and when playback was starved -- which happens constantly, because the
  server sends a turn as a handful of blobs and the head catches up with the
  last frame written -- it reported silence while the room was still loud with
  what had just played. Measured: frames carrying 4000-7000 of leak against a
  reference of 0.00. Every threshold ever tuned here was tuned against that.
  So `OutputLevelMeter.referenceAt` answers past the end of what was filed
  with the most recent window for up to 250 ms, kept separate from `levelAt`,
  which drives the avatar's mouth and must stay silent when nothing is
  scheduled; and the gate reads a bounded 240 ms look-back maximum rather than
  an exponential envelope, which bounds the delay instead of guessing it.

- **The bar follows the speaker, not the loudest thing it has said.** The leak
  is now a ratio -- microphone RMS per unit of reference -- read as the 90th
  percentile of the last two seconds, so a quiet passage is judged on what the
  speaker is doing in it. The absolute turn peak that used to *be* the bar is
  what rejected a real question at 5585 behind a bar of 8611 while the leak for
  that instant was 470; it survives only as a floor at `PEAK_FLOOR` of itself,
  a backstop for when the reference is missing entirely. Verified on a Galaxy
  A35 at 128 judged frames over 50 s of the assistant speaking into a silent
  room: zero false interruptions, with the bar resting at 4500-9500 and
  tracking the reference rather than sitting at the turn's maximum.

- **Ducking is gone, and should not come back.** Dropping the speaker to 15 %
  for 600 ms on a hint of speech was the answer while no bar could separate
  the leak from a voice. It worked, and it was audible -- a product whose
  subject is the Live API cannot dip its own voice to work out whether it is
  being spoken to, which is what the user said when they heard it. It also
  cost the platform canceller its model on every step, which produced four
  interruptions nobody made. With the reference fixed the bar has an honest
  margin at full volume, so the duck, `AudioPlayer.duckScale`,
  `EchoGate.isSuspectingSpeech` and the controller's duck window are all
  deleted. Reintroducing attenuation is a sign the reference has broken again:
  look there first.

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
