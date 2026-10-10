# FishMate AI

**A hands-free AI fishing companion built on the Gemini Live API.**

FishMate is not a chatbot with a fishing theme. Gemini Live *is* the interaction
layer: the angler talks, the assistant hears, sees what the camera sees, calls
tools against live weather and the angler's own history, and answers by voice —
while both hands stay on the rod.

```
User:  "Gemini, şu an ne kullanayım?"
AI:    "Su sakin, dalga on santim. Light spin takımla on bir gramlık minnow'u
        dene — kıyıya paralel, sabit tempoda. Bir vuruş alırsan söyle."

User:  (points camera at a lure) "Bu sahteyle ne yapayım?"
AI:    "Bu küçük bir soft plastic, yedi gramlık jighead'e takılı görünüyor.
        Çipura hedefliyorsan dibe yakın, iki saniye beklemeli çalıştır."
```

---

## What it does

| Feature | How it works |
|---|---|
| **Live fishing assistant** | Bidirectional audio over a Live API WebSocket with native-audio output, server-side VAD and barge-in |
| **Camera vision** | Throttled JPEG frames as realtime video input; the assistant reasons about lures, fish, water and structure |
| **Fish identification** | Species suggested *with* confidence and visual cues — never as certainty, always user-correctable |
| **Tackle analyzer / inventory** | The assistant only ever recommends gear the angler actually owns |
| **"What should I use?"** | A scored decision engine over real tackle + measured conditions + personal catch history |
| **Fishing sessions** | Start/finish by voice, with a weather snapshot and a generated report |
| **Catch log** | Photo, species, length, weight, lure, technique, conditions — all editable |
| **Fishing memory** | "Geçen sefer neyle levrek aldım?" answered from the database, not the model's imagination |
| **External context** | Live weather, wind, **wave height, swell and sea-surface temperature**, sunrise/sunset |
| **Honest regulations** | Will not state a size limit or closed season it cannot cite |

Turkish-first, with English built in; the architecture takes more languages
without code changes.

---

## Architecture at a glance

```
┌─────────────────────── Android (Kotlin / Compose) ───────────────────────┐
│  Fishing Mode UI                                                         │
│      │                                                                   │
│  FishingModeController ── AudioCapture (16 kHz PCM) ── AudioPlayer (24k)  │
│      │                 └─ CameraSource (throttled JPEG)                  │
│      │                                                                   │
│  LiveAiSession ──────────────────────────┐                               │
└──────────│───────────────────────────────│───────────────────────────────┘
           │ 1. POST /live/session         │ 2. wss:// direct, constrained
           │    (authenticated)            │    ephemeral token
           ▼                               ▼
┌──────────────────────────┐      ┌─────────────────────────┐
│   FishMate backend       │      │   Gemini Live API       │
│   (FastAPI)              │      │   gemini-3.8-live       │
│                          │      └───────────┬─────────────┘
│  • mints *constrained*   │                  │ 3. functionCall
│    ephemeral tokens      │                  ▼
│  • executes tools   ◄───────────── 4. device forwards the call
│  • fishing memory        │          (authenticated HTTPS)
│  • Open-Meteo + marine   │
│  • PostgreSQL            │
└──────────────────────────┘
```

Audio and video go **straight** to Gemini — proxying realtime media through our
backend would add latency for no benefit. What the backend keeps is the thing
that must not be on a phone: the real API key. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) and
[docs/SECURITY.md](docs/SECURITY.md).

---

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| JDK | 17 | `java -version` |
| Android SDK | platform **37.0**, build-tools **37.0.0** | AGP 9.4 requires build-tools 36+; OkHttp 5.5 requires compileSdk 37 |
| Python | 3.12+ | backend |
| [uv](https://docs.astral.sh/uv/) | latest | Python env management |
| Docker | any recent | local PostgreSQL |
| Gemini API key | — | [aistudio.google.com/apikey](https://aistudio.google.com/apikey) |

No Gradle install is needed — use the committed wrapper (`./gradlew`).

---

## Local setup

```bash
# 1. Environment file (generates AUTH_SECRET for you)
make env
#    then edit .env and set GEMINI_API_KEY=...

# 2. Backend dependencies
make setup-backend

# 3. Database
make db-up          # PostgreSQL on localhost:55432
make migrate        # apply Alembic migrations

# 4. Run the backend
make run            # http://localhost:8099 — docs at /docs
```

Verify:

```bash
curl -s localhost:8099/api/v1/health | python3 -m json.tool
# {"status": "ok", "live_api_configured": true, ...}
```

### Android

```bash
make android-build                 # assembles the debug APK
make android-install               # installs on a connected device/emulator
```

The debug build points at `http://10.0.2.2:8099/` (the host machine as seen
from the emulator). Override per machine in `android/local.properties`:

```properties
fishmate.apiBaseUrl=http://192.168.1.20:8099/
```

or per build: `./gradlew assembleDebug -PapiBaseUrl=https://api.example.com/`.

> Ports 55432 and 8099 are non-default on purpose — 5432 and 8080 collide with
> almost every other project. Change `DB_HOST_PORT` / `API_HOST_PORT` in `.env`.

---

## Environment variables

Everything is documented in [`.env.example`](.env.example). The ones you must set:

| Variable | Why |
|---|---|
| `GEMINI_API_KEY` | Server-side only. Exchanged for short-lived client tokens; never shipped in the APK. |
| `AUTH_SECRET` | Signs the app's own session tokens. `openssl rand -hex 32`. |
| `DATABASE_URL` | Async driver; the sync URL for Alembic is derived automatically. |

Optional but worth knowing:

| Variable | Default | Notes |
|---|---|---|
| `GEMINI_LIVE_MODEL` | `gemini-3.8-live` | `gemini-3.8-live-extended-thinking` for more reasoning, higher latency |
| `WEATHER_PROVIDER` | `open_meteo` | **No API key needed**, and includes marine data. `mock` for demos/tests. |
| `DEMO_MODE_ENABLED` | `true` | Must be `false` in production; startup refuses otherwise |

`.env` is gitignored. `make secrets-audit` fails the build if a key pattern or
`.env` ever gets staged.

---

## Testing

```bash
make test-backend     # 246 tests
make cov              # with coverage
make test-android     # 63 unit tests
make lint             # ruff
make fmt-check        # formatting
make check            # everything CI runs for the backend
cd android && ./gradlew lintDebug
```

To verify the integration against the **real** Live API (needs a key):

```bash
export GEMINI_API_KEY=...
make verify-live
```

Automated tests never contact Gemini or a real weather API. The Live API sits
behind `LiveAiSession`, with `MockLiveAiSession` driving interruption, tool
calls, `goAway` and token expiry deterministically. See
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#testing-strategy).

---

## Demo / presentation

```bash
curl -X POST localhost:8099/api/v1/demo/seed -H "Authorization: Bearer $TOKEN"
```

Then in the app: **Settings → Demo mode**. Gemini Live stays real in every
scenario; only the weather provider and the seeded history are made
deterministic, so a conference network cannot break the narrative.

Full runsheet: [docs/GOOGLE_DEMO_SCRIPT.md](docs/GOOGLE_DEMO_SCRIPT.md).

---

## Deployment

Cloud Run + Cloud SQL, one service, no Kubernetes. Commands in
[docs/DEPLOYMENT.md](docs/DEPLOYMENT.md).

---

## Documentation

| Document | What's in it |
|---|---|
| [ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layering, abstractions, and the decisions behind them |
| [GEMINI_LIVE_IMPLEMENTATION.md](docs/GEMINI_LIVE_IMPLEMENTATION.md) | **Audit reference**: exact model, SDK, config, and the citation behind each setting |
| [GDE_GEMINI_LIVE_FEATURE_MATRIX.md](docs/GDE_GEMINI_LIVE_FEATURE_MATRIX.md) | Capability-by-capability matrix with file:line evidence |
| [GEMINI_LIVE.md](docs/GEMINI_LIVE.md) | Verified Live API facts, session management, audio, cost control |
| [SECURITY.md](docs/SECURITY.md) | The ephemeral-token model and why constraints are the whole point |
| [PRIVACY.md](docs/PRIVACY.md) | Location handling, what leaves the device, deletion |
| [DATABASE.md](docs/DATABASE.md) | Schema, indexes, migrations |
| [DEPLOYMENT.md](docs/DEPLOYMENT.md) | Cloud Run, Cloud SQL, secrets, exact commands |
| [DEMO.md](docs/DEMO.md) | How Demo Mode works and what it does *not* fake |
| [GOOGLE_DEMO_SCRIPT.md](docs/GOOGLE_DEMO_SCRIPT.md) | 5–8 minute presentation runsheet with fallbacks |
| [TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Toolchain constraints and common failures |
| [ROADMAP.md](docs/ROADMAP.md) | Known limitations and what comes next |

---

## Project layout

```
.
├── backend/              FastAPI service
│   ├── app/
│   │   ├── api/v1/       HTTP routes
│   │   ├── core/         errors, security, observability
│   │   ├── db/           models, session, UTC datetime type
│   │   ├── repositories/ data access
│   │   ├── services/     live tokens, weather, memory, recommendation, regulations
│   │   ├── tools/        Gemini tool declarations, schemas, registry
│   │   └── resources/    system instructions (tr/en), regulation data
│   ├── alembic/          migrations
│   └── tests/            231 tests
├── android/
│   └── app/src/main/java/com/fishmate/ai/
│       ├── live/         LiveAiSession, Gemini WebSocket, tool orchestrator, controller
│       ├── audio/        PCM capture, playback, routing/focus
│       ├── camera/       CameraSource abstraction + CameraX implementation
│       ├── data/         Room, Retrofit, repositories, offline sync
│       └── ui/           Compose screens and theme
└── docs/
```

## Licence

Not yet licensed for redistribution. See [docs/ROADMAP.md](docs/ROADMAP.md).
