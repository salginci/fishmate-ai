# Security

## The central problem

The Android app streams audio and video directly to the Gemini Live API.
Proxying realtime media through our backend would add latency and cost for no
benefit. But a mobile app **cannot hold a permanent `GEMINI_API_KEY`** — an APK
is trivially unpacked, and a leaked key is someone else's bill and someone
else's traffic on our quota.

So the backend holds the real key and hands the device an **ephemeral token**.

```
Android                     FishMate backend              Gemini
   │                               │                         │
   │ 1. POST /auth/login           │                          │
   │──────────────────────────────▶│                          │
   │◀──── app session JWT ─────────│                          │
   │                               │                          │
   │ 2. POST /live/session         │                          │
   │──────────────────────────────▶│ 3. auth_tokens.create    │
   │                               │    (+ constraints)       │
   │                               │─────────────────────────▶│
   │                               │◀──── ephemeral token ────│
   │◀──── token + locked setup ────│                          │
   │                               │                          │
   │ 4. wss://...?access_token=... │                          │
   │─────────────────────────────────────────────────────────▶│
```

The permanent key never leaves the server. The APK contains no Gemini
credential of any kind.

---

## Constraints are the whole point

An ephemeral token minted **without** `liveConnectConstraints` is nearly as
dangerous as leaking the key: the holder can connect with any model, any system
instruction and any tool set, billed to us. This is a documented failure mode,
and it is the mistake this implementation is explicitly built to avoid.

`app/services/live_tokens.py` locks:

```python
live_connect_constraints=types.LiveConnectConstraints(
    model=model_name,          # pinned
    config=typed_config,       # pinned: modalities, voice, tools,
),                             #         system instruction, session mgmt
lock_additional_fields=[       # the client cannot set these either
    "temperature", "top_p", "top_k", "max_output_tokens", "seed",
],
```

What that buys:

| Locked | Consequence |
|---|---|
| **System instruction** | The safety rules — observation/inference separation, never invent regulations — hold even against a modified APK. A tampered client cannot jailbreak the prompt, because it never supplies one. |
| **Tool set** | Fixed to our twelve declarations plus `google_search`. A client cannot invent a tool. |
| **Model** | Cannot be swapped for a more expensive one. |
| **Response modality / voice** | Cannot be changed. |
| **Session management** | Resumption and compression cannot be disabled. |
| `max_output_tokens`, `temperature`, … | Cannot be raised to inflate cost or to destabilise the instruction. |

Because the config is locked, the client does **not** invent its own setup
message. The backend returns the exact `setup` payload to echo; the only thing
the client injects is the session-resumption handle, which is client-side state
the server cannot know.

Token lifetime policy (`.env`):

| Setting | Default | Meaning |
|---|---|---|
| `EPHEMERAL_TOKEN_NEW_SESSION_EXPIRE_SECONDS` | 60 | window to *open* a session |
| `EPHEMERAL_TOKEN_EXPIRE_MINUTES` | 30 | window to *use* an open connection |
| `EPHEMERAL_TOKEN_USES` | 1 | one session per token |

Tests that enforce all of this: `tests/test_live_tokens.py::TestMinting::test_mint_locks_model_config_and_extra_fields`.

---

## Application authentication

* Passwords: **bcrypt**, cost 12, used directly rather than through `passlib`
  (unmaintained, and broken against bcrypt ≥ 4.1).
* bcrypt silently truncates past 72 bytes, so longer passwords are **rejected**
  rather than accepted — otherwise a 72-byte prefix would authenticate the
  account.
* Sessions: HS256 JWT, `AUTH_SECRET` required and ≥ 32 chars; production
  startup **fails** without it.
* Login failures are indistinguishable between "no such account" and "wrong
  password", so the API cannot be used to enumerate users. Enforced by a
  parametrised test.
* No Gemini credential is ever persisted on the device. The app session token
  lives in DataStore; Live tokens are memory-only and re-minted on demand.

---

## Tool-layer hardening

| Control | Where |
|---|---|
| Every argument validated before execution | `app/tools/schemas.py`, Pydantic with `extra="forbid"` |
| No model-generated code or SQL ever executed | tools are a fixed hand-written set |
| Bounded numeric arguments | `limit ≤ 20`, `length_cm ≤ 400`, `ai_confidence ∈ [0,1]`, lat/lon ranges |
| Per-user scoping | `ToolContext` carries the authenticated user; **no tool takes a `user_id` argument**, so one user cannot read another's data |
| Full audit trail | `tool_executions`: tool, validated args, outcome, duration |
| Failures never raise into the conversation | a tool error becomes a `ToolResult(ok=false)` the model can narrate |
| Model cannot self-certify | `log_catch` cannot mark its own guess `species_confirmed_by_user` |

Cross-user isolation is tested directly
(`test_user_cannot_read_another_users_data`).

---

## Logging and redaction

Enforced in `app/core/observability.py` and `core/Logging.kt`:

* Keys matching `api_key|token|secret|password|authorization|credential` are
  replaced with `«redacted»`.
* **Raw audio and image bytes are never logged**, only sizes.
* Coordinates are coarsened to two decimals (~1 km) before any log line, and
  formatted with `Locale.ROOT` so a comma-decimal locale cannot corrupt the
  pair.
* Provider exception messages are **not** propagated: a Gemini error can
  contain the API key in a URL, so only the exception *type* is logged and the
  user gets a generic message.
* OkHttp logging is `BASIC`, not `BODY` — request bodies carry catch notes and
  positions, and `BODY` would print the Live token.
* Audit rows store coarsened coordinates too.

---

## Transport

* Production is HTTPS/WSS only. `network_security_config.xml` sets
  `cleartextTrafficPermitted="false"` and whitelists cleartext **only** for
  `10.0.2.2`, `localhost` and `127.0.0.1`, so a debug build can reach a local
  backend without weakening the shipped app.
* `allowBackup="false"` plus explicit `data_extraction_rules.xml` exclusions:
  the local database holds catch history and fishing spots, and the auth store
  holds a session token. None of it should ride along in a cloud backup.

---

## Secrets hygiene

* `.gitignore` covers `.env`, `*.pem`, `*.key`, `*.jks`, `*.keystore`,
  `google-services.json`, `service-account*.json`.
* `.env.example` documents every variable with **no real values**.
* `make secrets-audit` fails if a Google API-key pattern is tracked or if
  `.env` is in the index; CI runs it on every push.
* `local.properties` (SDK path, per-machine API URL) is gitignored.

---

## Production startup refusal

`Settings.startup_problems()` blocks a production boot on:

* missing or short `AUTH_SECRET`
* `CORS_ORIGINS=*`
* `DEMO_MODE_ENABLED=true`

Better a failed deploy than a live service with a wildcard CORS policy and
seeding endpoints exposed. In production the demo router is not even registered,
and `/docs` and `/openapi.json` are disabled.

---

## Known gaps

Honest list, tracked in [ROADMAP.md](ROADMAP.md):

1. **No device attestation.** Any valid account can request Live tokens. Play
   Integrity or Firebase App Check would bind tokens to genuine installs.
2. **No per-user rate limiting on token minting.** The error type exists
   (`RateLimitedError`) but nothing enforces a quota yet; a single account could
   mint tokens in a loop. This is the most important gap before public release.
3. **Release signing uses the debug key.** Deliberate for a demo build; a real
   release needs a keystore in CI secrets.
4. **Photos are device-local.** No encrypted backup path yet, so a lost phone
   loses catch photos (but not catch records).
