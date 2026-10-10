# Troubleshooting

## Toolchain version constraints

These were all discovered by building, not by reading. They are the ones most
likely to bite someone setting the project up.

| Constraint | Detail |
|---|---|
| **AGP 9.4 needs build-tools 36+** | And OkHttp 5.5 needs **compileSdk 37**. Install `platforms;android-37.0` and `build-tools;37.0.0`. |
| **Platform naming changed** | It is `platforms;android-37.0`, not `platforms;android-37`. `sdkmanager --install "platforms;android-37"` fails with "Failed to find package". |
| **Compose BOM 2026.09 needs AGP ≥ 9.1** | AGP 8.13 fails `checkDebugAarMetadata` with `androidx.compose.ui:ui-android requires Android Gradle plugin 9.1.0 or higher`. |
| **Hilt 2.58+ needs AGP 9** | On AGP 8 it fails with *"The Hilt Android Gradle plugin is only compatible with AGP 9.0.0 or higher"*. 2.57.2 is the last AGP-8-compatible release. |
| **AGP 9 needs `android.newDsl=false` here** | AGP 9's new DSL is incompatible with `org.jetbrains.kotlin.android`. The alternative is built-in Kotlin, but KSP — which Room **and** Hilt both need — is not wired for it yet. The opt-out is removed in AGP 10; see [ROADMAP.md](ROADMAP.md). |
| **AGP 9.4 needs Gradle ≥ 9.6** | The wrapper is pinned to 9.8.0. |
| **JDK 17** | Works for AGP 9.4. JDK 21 (Android Studio's bundled JBR) also works. |
| **No `gradle` CLI needed** | Use `./gradlew`. |

### `passlib` is unusable

`passlib` 1.7.4 is unmaintained and its bcrypt backend probe breaks against
`bcrypt >= 4.1`:

```
ValueError: password cannot be longer than 72 bytes
AttributeError: module 'bcrypt' has no attribute '__about__'
```

…even when hashing a 17-character password. The backend uses the `bcrypt`
library directly. Do not reintroduce passlib.

---

## Backend

### `BUILD FAILED` / startup refuses in production

```
RuntimeError: Refusing to start in production with configuration problems:
AUTH_SECRET is not set; CORS_ORIGINS must be explicit in production
```

Working as intended. Set `AUTH_SECRET` (`openssl rand -hex 32`), set explicit
`CORS_ORIGINS`, and `DEMO_MODE_ENABLED=false`.

### Port already allocated

```
Bind for 0.0.0.0:5432 failed: port is already allocated
```

5432 and 8080 collide with almost everything. Defaults here are **55432** and
**8099**; override `DB_HOST_PORT` / `API_HOST_PORT` in `.env`.

### `live_api_configured: false`

`GEMINI_API_KEY` is unset. The rest of the app still works — tackle, sessions,
catches, weather — but Fishing Mode will report *"Gemini is not configured on
the server yet."*

### Weather returns no sea data

Expected inland. Open-Meteo's marine grid excludes inland coordinates, and the
tool payload says so explicitly:

```json
{"note": "No marine/sea-state data is available for these coordinates
          (likely an inland location)."}
```

The marine endpoint echoes `time`/`interval` even for inland points, so
presence of the response does not mean presence of data — `sea_data_available`
checks for an actual measurement.

### `can't subtract offset-naive and offset-aware datetimes`

Should not recur: `UtcDateTime` normalises both backends. If it reappears, a
new model used `DateTime(timezone=True)` directly instead of `UtcDateTime`.

### Migration drift test fails

```
Migrations have drifted from the models. Run:
  alembic revision --autogenerate -m 'describe change'
```

A model changed without a migration. The test exists because the drift
otherwise works in tests and fails in production.

### Coverage looks implausibly low

Check `concurrency = ["greenlet", "thread"]` is still in
`[tool.coverage.run]`. Without it, coverage.py cannot see through SQLAlchemy's
asyncio greenlets and under-reports anything that awaits the database — it
reported 48% for a module at 97%.

---

## Android

### `Unresolved reference` after editing Gradle files

```bash
./gradlew --stop && ./gradlew clean :app:assembleDebug
```

### Lint: `LocalContextGetResourceValueCall` / `NonObservableLocale`

Reading resources or locale non-observably in a composable. Use
`stringResource` / `pluralStringResource`, and `rememberAppLocale()` for
locale-sensitive formatting. This is a real bug for a bilingual app: cached
values survive a language change.

### "Microphone access is required for Fishing Mode."

Grant `RECORD_AUDIO`. The mic is the one hard requirement; camera and location
are optional and the app works without them.

### The assistant interrupts itself constantly

The echo canceller is not engaged. `AudioCapture` must use
`MediaRecorder.AudioSource.VOICE_COMMUNICATION` (not `MIC`) and `AudioPlayer`
must use `AudioAttributes.USAGE_VOICE_COMMUNICATION`. With `MIC`, the model
hears itself through the speaker and server-side VAD treats it as the user
talking.

### The voice sounds slow and deep

Playing 24 kHz output through a 16 kHz `AudioTrack`. Input is 16 kHz, output is
24 kHz — they are different, and both come from the server in
`LiveSessionResponse`.

### "Reconnecting…" every few minutes

Normal. A Live connection lasts about ten minutes and the server sends
`goAway`; the client reconnects proactively with its resumption handle. If it
instead shows an error, the handle is not being stored — check for
`LiveEvent.ResumptionHandle` reaching `FishingModeState.resumeHandle`.

### Tool calls do nothing

Check in order:

1. The device can reach the backend. `BuildConfig.API_BASE_URL` in a debug
   build is `http://10.0.2.2:8099/` (emulator → host). On a physical device set
   `fishmate.apiBaseUrl` in `local.properties` to your machine's LAN address.
2. Cleartext. `network_security_config.xml` permits it only for `10.0.2.2`,
   `localhost` and `127.0.0.1`. A LAN IP over `http://` is blocked by design —
   add the host to the config for local testing, or use HTTPS.
3. The backend log. `tools.executed` should appear per call; `tools.unknown`
   means a name mismatch, `tools.invalid_args` a schema mismatch.

### The app recommends gear the user does not own

It should be impossible — `recommend_setup` only scores items from
`get_tackle_inventory`, and an empty inventory returns an explicit
`instruction_to_model` telling the model to ask instead. If it happens, the
model is answering from its own knowledge rather than the tool result; check
that the tool is actually being called.

### Release build fails after adding a serializable class

Add a `-keep` rule in `proguard-rules.pro`. DTOs under
`com.fishmate.ai.data.remote.dto` and `com.fishmate.ai.live.protocol` are
already covered.

### Lint warns `Aligned16KB` about `libmockkjvmtiagent.so`

From `io.mockk:mockk-agent-android`, a **test** dependency. It is not in the
shipped APK and cannot be fixed from here.

---

## Verifying the whole stack quickly

```bash
# backend
make check                                  # lint + format + 231 tests
curl -s localhost:8099/api/v1/health | python3 -m json.tool

# a real tool round-trip
TOKEN=$(curl -s -X POST localhost:8099/api/v1/auth/login \
  -H 'content-type: application/json' \
  -d '{"email":"you@example.com","password":"..."}' \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["access_token"])')

curl -s -X POST localhost:8099/api/v1/tools/execute \
  -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"name":"get_current_conditions","args":{"latitude":38.46,"longitude":27.09}}' \
  | python3 -m json.tool

# android
cd android && ./gradlew testDebugUnitTest lintDebug assembleDebug
```
