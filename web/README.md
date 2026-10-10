# FishMate Web

FishMate AI in the browser. The same product idea as the Android app, with
**Gemini Live as the interaction layer**: the page opens its own WebSocket to
Google, streams microphone audio and camera stills, and plays native audio
back.

Scope is Fishing Mode plus what you need to reach it — sign-in and a home
screen with live conditions and recent catches. Tackle management, the catch
log and settings stay on Android for now.

## Running it locally

```bash
# 1. backend (needs GEMINI_API_KEY in the repo-root .env)
make db-up && make migrate
make run                     # :8099

# 2. web
cd web
cp .env.example .env.local   # optional: pre-fills the sign-in form
npm install
npm run dev                  # :5178
```

The dev server proxies `/api` to `http://localhost:8099`, so the browser sees a
same-origin API and no CORS policy is involved. Production sets an explicit
`CORS_ORIGINS` and refuses to boot on a wildcard, so developing against a
wildcard would hide a misconfiguration until deploy.

Point it at another backend with `FISHMATE_API=https://… npm run dev`, or build
against one with `VITE_API_BASE`.

**Browser requirements.** `getUserMedia` needs a secure context: `localhost`
counts, a LAN IP does not. Testing from a phone on the same network therefore
needs a tunnel (or `vite --host` behind HTTPS), otherwise the microphone call
fails with no obvious cause.

**Node.** Vite 7 wants Node 20.19+ or 22.12+. It runs on 20.15 with a warning,
but upgrading removes a class of confusing build failures.

## Tests

```bash
npm test          # unit tests; never contacts Google
npm run typecheck
npm run build

FISHMATE_VERIFY_LIVE=1 npm test    # opens a REAL Gemini Live session
```

The last one is the web counterpart of the backend's `make verify-live`. It
drives this client's own `LiveSession` against the live service and asserts
native audio comes back, which is the only way to catch a malformed `setup`
frame — every unit test in the world will accept one the server rejects.

## How it is put together

Deliberately mirrors the Android names, so the two clients can be read against
each other:

| Web | Android | Does |
|---|---|---|
| `live/LiveSession.ts` | `live/GeminiLiveSession.kt` | WebSocket, wire frames, reconnection |
| `live/FishingModeController.ts` | `live/FishingModeController.kt` | Mic, camera, tool loop → one state |
| `audio/AudioCapture.ts` | `audio/AudioCapture.kt` | 16 kHz PCM capture |
| `audio/AudioPlayer.ts` | `audio/AudioPlayer.kt` | 24 kHz playback, barge-in flush |
| `audio/EchoGate.ts` | `audio/EchoGate.kt` | Keeps the model from interrupting itself |
| `camera/CameraSource.ts` | `camera/CameraSource.kt` | Throttled JPEG stills |

The UI knows nothing about WebSockets and the Live layer knows nothing about
React — the same split as Android.

### Things that are easy to get wrong here

- **The `setup` frame has no `config` wrapper.** That is an SDK abstraction and
  the service rejects it. The backend sends the exact frame; this client passes
  it through and only injects the resumption handle.
- **Ephemeral tokens carry two deadlines.** `expires_at` keeps an open session
  alive for around 30 minutes; `new_session_expires_at` allows *opening* one for
  about a minute. Reconnect logic must check both, or any drop longer than a
  minute becomes permanent.
- **The audio worklet must stay a real file.** Vite would inline a module this
  small as a `data:` URL, and `audioWorklet.addModule()` does not reliably
  accept one — which breaks only in a production build, only once the
  microphone is switched on.
- **Echo is the hard part of hands-free.** The browser's `echoCancellation` is
  better than Android's but still loses to laptop speakers, and the failure is
  a conversation that interrupts itself every few seconds *and* answers in
  languages nobody spoke. See `docs/GEMINI_LIVE_IMPLEMENTATION.md`.
