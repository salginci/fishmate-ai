# Roadmap and known limitations

Written honestly: what is finished, what is deliberately unfinished, and what
is simply missing.

## Known limitations

### Security (highest priority)

| Gap | Impact | Fix |
|---|---|---|
| **No rate limiting on token minting** | A valid account could mint Live tokens in a loop and run up cost. `RateLimitedError` exists but nothing enforces a quota. | Per-user token budget with a Redis or Postgres counter. **Do this before any public release.** |
| **No device attestation** | Any account can request tokens from any client. | Play Integrity or Firebase App Check, verified at `/live/session`. |
| **Release signs with the debug key** | Fine for a demo build, not shippable. | Keystore in CI secrets. |

### Functional

| Gap | Notes |
|---|---|
| **No verified regulation data** | `resources/regulations/tr.json` ships with `species: {}` on purpose. Until an operator transcribes the official circular with citations, the tool refuses and names the authority. The *architecture* is complete; the *data* is deliberately absent. |
| **Photos are device-local** | Catch photos are referenced by local URI with no backup path, so a lost phone loses photos (not records). |
| **No tide data** | Matters less in the Mediterranean, but `WeatherProvider` is the place for it. |
| **Tackle analyzer has no dedicated screen** | The capability works through the Live assistant (point the camera at gear and ask), and AI-created items are flagged `ai_identified` for confirmation, but there is no separate "analyze my tackle" flow. |
| **Wake word is a no-op** | Deliberate — see below. |
| **English system instruction is less developed than Turkish** | Turkish is the primary target; the English prompt is complete but has had less tuning on fishing idiom. |
| **No instrumented UI tests** | The dependencies are wired and the state machine is covered by unit tests, but there is no Compose UI test suite or device-farm run yet. |

### Technical debt

| Item | Why it exists | When it must be paid |
|---|---|---|
| `android.newDsl=false` | AGP 9's new DSL is incompatible with `kotlin-android`, and KSP (needed by Room *and* Hilt) is not wired for built-in Kotlin. | The opt-out is **removed in AGP 10**. Migrate once KSP supports built-in Kotlin. |
| Hilt pinned by AGP compatibility | Resolved — on AGP 9 we are on Hilt 2.60.1. | — |
| `SyncManager` stops at the first failure | Correct for ordering, but one stuck operation blocks the queue until it exhausts its 5 attempts. | Per-entity queues if it becomes a problem in practice. |

---

## Why the wake word is a no-op

Not an oversight. Two real constraints:

1. **Android will not permit arbitrary background microphone access.**
   Microphone use requires a foreground service with a visible notification,
   and the platform may still stop it. Pretending otherwise would be dishonest
   UX and would not survive review.
2. **A cloud connection must never be used just to detect a keyword.** That
   would stream audio and cost continuously for no benefit.

And once Fishing Mode is running, no wake word is needed — server-side VAD
handles turn-taking, so the angler just talks. `WakeWordEngine` exists for the
genuinely useful future case: waking the app *from idle*, on-device, with
something like Porcupine.

---

## Next, in order

### 1. Make it safe to release (weeks)

* Per-user rate limiting on `/live/session`
* Play Integrity / App Check
* Real release signing in CI
* Crash reporting wired to the existing `Log2.e` integration point

### 2. Make it more useful (weeks)

* **Tackle analyzer flow** — a dedicated "show me your gear" screen that
  populates the inventory from camera analysis, since an empty tackle box is
  the main thing blocking good recommendations for a new user
* **Transcribed regulation data for Türkiye**, with citations and effective
  dates, so the tool can answer instead of refusing
* **Photo backup** — opt-in, encrypted
* Tide and marine forecast providers
* Catch statistics: best conditions, best times, per-spot productivity

### 3. Extend the interaction model (months)

* **Smart glasses** via `CameraSource` — the natural home for this product;
  the phone camera is a stand-in for a head-mounted one
* On-device wake word
* Fishing spot map (private by default; no social features without a clear
  reason)
* Session hand-off between devices using the Live resumption handle

### 4. Learn from data (longer)

* Correlate `ai_recommendations.followed_by_catch` to measure whether the
  advice actually works — the schema already records it, nothing reads it yet
* Personal patterns: "you catch more seabass on falling pressure"
* Community catch data, if and only if privacy can be preserved properly

---

## Explicitly not planned

| Not doing | Why |
|---|---|
| Social feed / public catch map | Anglers do not publish their marks. A spot map would be the feature that makes the app unsafe to use. |
| Always-on listening | Android will not allow it, and the cost model makes it absurd. |
| Stating regulations without a source | A wrong size limit is a fine for the user. |
| Proxying audio through the backend | Latency and cost for no benefit; ephemeral tokens exist precisely so we do not have to. |

---

## Licence

Not currently licensed for redistribution. Before any open-source release:
species data provenance, Open-Meteo attribution terms, and the Gemini API terms
for a shipped application all need checking.
