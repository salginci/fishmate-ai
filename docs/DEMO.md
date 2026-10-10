# Demo Mode

## What it does and does not fake

The brief is explicit: **do not fake Gemini Live.** So Demo Mode changes
nothing about the Live session, the tool layer, the database path or the UI. A
demo run exercises exactly the production code.

What it *does* control is the two things a stage cannot guarantee:

| Controlled | Why |
|---|---|
| **External data** | The weather provider is swapped for `MockWeatherProvider`, derived deterministically from the coordinates. "Wind 14 km/h" is identical in rehearsal and on stage, and a flaky conference network cannot break the narrative. |
| **History** | "What did I use last time for seabass?" needs a past. `POST /demo/seed` writes a believable six-week history so the memory tool has real rows to analyse. |

| Never faked |
|---|
| The Gemini Live connection, audio, or model output |
| The tool-calling loop |
| Any database write |
| The recommendation engine |
| The regulation refusal |

---

## Endpoints

All require authentication, and all are **absent in production** — the router
is not registered when `ENVIRONMENT=production` or `DEMO_MODE_ENABLED=false`,
and `Settings.startup_problems()` refuses to boot production with demo mode on.

| Endpoint | Purpose |
|---|---|
| `GET /api/v1/demo/scenarios` | The runsheet, served to the app's Demo screen so the script can change without a rebuild |
| `POST /api/v1/demo/seed?reset=false` | Write the deterministic history (idempotent for tackle) |
| `POST /api/v1/demo/mock-weather?enabled=true` | Swap the weather provider |

---

## The seeded history

Six completed sessions at **Bostanlı, İzmir** over ~6 weeks, eight catches:

| Days ago | Target | Catches |
|---|---|---|
| 38 | levrek | 1 × 46 cm on *Minnow 11 cm / 11 g* |
| 31 | çipura | 2 × on *Soft plastic 7 cm* |
| 24 | levrek | 2 × (52 cm, 38 cm) on *Minnow 11 cm / 11 g* |
| 17 | istavrit | 1 × on *Jighead 3 g* (LRF) |
| 11 | levrek | 0 — a blank session, because real history has them |
| 5 | çipura + levrek | 1 çipura on *Jighead 7 g*, 1 levrek on **Metal jig 20 g** |

Shaped on purpose:

* **Three of four levrek came to the 11 g minnow, one to a metal jig.** So the
  memory tool has to actually count. If it just echoed the most recent lure it
  would say "metal jig", and the demo would be a lie.
* Catches cluster late in each session, so the generated report's "most
  activity happened in the final hour" observation is genuinely derived.
* One blank session, so the history is not implausibly perfect.
* Eleven tackle items, so `recommend_setup` has a real inventory and never has
  to say "I don't know what you have".

Asserted in `tests/test_demo.py::test_seeded_history_answers_scenario_six`.

---

## Rehearsing

```bash
# Deterministic + fresh
curl -X POST "localhost:8099/api/v1/demo/seed?reset=true" -H "Authorization: Bearer $TOKEN"
curl -X POST "localhost:8099/api/v1/demo/mock-weather?enabled=true" -H "Authorization: Bearer $TOKEN"
```

Then in the app: **Settings → Demo mode** (debug builds only) shows all eight
scenarios with the exact line to say, the capability being demonstrated, and
what to expect — plus the fallback paragraph.

Restore live weather afterwards:

```bash
curl -X POST "localhost:8099/api/v1/demo/mock-weather?enabled=false" -H "Authorization: Bearer $TOKEN"
```

---

## Watch the logs

The backend log is the best corroboration that the tool loop is real:

```
[info] tools.executed  tool=recommend_setup  duration_ms=412  mutates=False
[info] tools.executed  tool=log_catch        duration_ms=38   mutates=True
[info] live.session_issued  model=gemini-3.8-live  duration_ceiling=2min_av
```

Keep a terminal visible. "It called a tool" is far more convincing with the
line on screen than as a claim.
