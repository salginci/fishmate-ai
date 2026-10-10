# Google presentation runsheet

**Target length: 5–8 minutes.** Eight scenarios, each demonstrating a distinct
Gemini Live capability. Every scenario runs through the real Live API — nothing
about the model is faked.

## Before you start

```bash
# 1. Backend up, migrations applied
make db-up && make migrate && make run

# 2. Sign in on the device, then seed deterministic history
curl -X POST localhost:8099/api/v1/demo/seed \
     -H "Authorization: Bearer $TOKEN"

# 3. Optional but recommended on a conference network:
curl -X POST localhost:8099/api/v1/demo/mock-weather?enabled=true \
     -H "Authorization: Bearer $TOKEN"
```

Checklist:

- [ ] Bluetooth earbuds paired **and** audio confirmed (play something)
- [ ] Phone volume up; Do Not Disturb **on** (a call steals audio focus)
- [ ] A lure and, if possible, a fish photo on a second screen to point at
- [ ] Settings → Demo mode open on a second device as a teleprompter
- [ ] Backend logs visible — the tool calls are the proof

Opening line:

> "This is FishMate. It is not a chatbot with a fishing theme — Gemini Live is
> the interface. I'm going to talk to it, show it things, and it's going to
> look up real data and remember what I caught last month. Everything you hear
> is the live model."

---

## Scenario 1 — Voice conversation + function calling (~60 s)

**Capability:** bidirectional audio, native audio output, server-side VAD,
function calling into a decision engine.

Tap **Start fishing**. Wait for *Dinliyorum* (Listening).

**Say:** "Gemini, şu an ne kullanayım?"

**Expect:** a one-or-two sentence spoken recommendation naming a lure the user
actually owns, citing measured wind or wave, with one reason.

**Point out:** the `recommend_setup` chip appearing under the orb, and
`tools.executed tool=recommend_setup` in the backend log.

> "It didn't guess. It called a tool that read my real tackle box, the measured
> wave height, and what I've actually caught fish on."

---

## Scenario 2 — Barge-in (~20 s)

**Capability:** interruption handling.

While it is mid-sentence, **talk over it**: "Tamam, anladım."

**Expect:** playback stops immediately and it starts listening.

> "That's `serverContent.interrupted`. We flush the audio queue *and* the
> AudioTrack — otherwise it keeps talking over you for a second and the whole
> illusion collapses."

---

## Scenario 3 — Camera vision (~60 s)

**Capability:** realtime video input, multimodal reasoning.

Tap the camera control, point at a lure.

**Say:** "Bu sahteyle ne yapayım?"

**Expect:** an observation of what is visible, a hedged inference, then a
retrieve suggestion — in that order.

> "Notice the structure of the answer: what it can see, then what it *infers*,
> then what it recommends. That separation is enforced in the system
> instruction, and the system instruction is locked into the token — a modified
> APK can't remove it."

---

## Scenario 4 — Identification with calibrated uncertainty (~45 s)

**Capability:** vision + honest confidence.

Point at a fish (photo is fine).

**Say:** "Bu ne balığı?"

**Expect:** a likely species, the visual cues behind it, and an offer to look
closer. **Not** a flat assertion.

> "It won't say 'this is definitely a seabream'. It gives a likelihood, the
> cues it used, and asks for a better angle. When we log it, that confidence is
> stored and the user can overrule it."

---

## Scenario 5 — Stateful function calling (~45 s)

**Capability:** tool calls that mutate state.

**Say:** "Av seansını başlat. Bostanlı'dayım, levrek arıyorum."

**Expect:** brief spoken confirmation. A weather snapshot is captured.

**Then say:** "Bir vuruş aldım ama yemedi."

**Expect:** a short acknowledgement; an observation is recorded with a
timestamp.

---

## Scenario 6 — Logging a catch (~45 s)

**Capability:** multi-tool turn, editable AI output.

**Say:** "Bunu kaydet — kırk iki santim, on bir gramlık minnow ile."

**Expect:** confirmation, species stated as a likelihood, and a mention that it
can be corrected.

**Show:** Catch log → the new entry carries an `AI 74%` badge and a
**"That's correct" / "Change species"** pair.

> "AI-generated data is never immutable here. Correcting the species clears the
> model's confidence, because that number no longer describes this row."

---

## Scenario 7 — Fishing memory (~45 s) ★ the one to land

**Capability:** retrieval over persisted history.

**Say:** "Geçen sefer neyle levrek aldım?"

**Expect:** something concrete — *"Son dört levrek kaydında üçü on bir gramlık
minnow'la geldi, çoğu akşamüstü saatlerinde."*

> "That's computed in Python from four real rows, not recalled by the model.
> Three of four on the minnow, one on the metal jig — if I'd seeded it
> differently it would say something different. We don't stuff the catch log
> into the context window: it would cost a fortune, blow the context on a long
> session, and go stale the moment I log a fish."

---

## Scenario 8 — Honest refusal (~40 s) ★ the one that builds trust

**Capability:** grounded refusal.

**Say:** "Levrek için yasak boy kaç santim?"

**Expect:** a clear "I can't confirm that from a verified source", plus the
Ministry of Agriculture and Forestry as the authority. **No number.**

> "This is the behaviour I'm proudest of. A wrong size limit isn't a bad
> answer — it's a fine, or a confiscated rod. So the regulation tool's default
> answer is 'not verified', and `verified=true` is only ever set for curated
> data with a citation and an effective date. The shipped data file is
> deliberately empty."

---

## Close — session report (~30 s)

**Say:** "Seansı bitir."

**Expect:** spoken headline numbers (duration, catches), full report on screen.

**Closing line:**

> "Two things to take away. First, the interaction model: I never touched the
> phone after 'Start fishing'. Second, the engineering: the token the app holds
> is locked to one model, one system instruction and twelve tools, it expires in
> thirty minutes, and the connection it opens gets rotated by the server every
> ten — so session resumption isn't an optimisation, it's the only reason a
> two-hour conversation works at all."

---

## Fallbacks

| If this happens | Do this | Say this |
|---|---|---|
| **"Reconnecting…" appears** | Nothing. Wait ~2 s. | "That's the server rotating the connection — about every ten minutes. It resumes with a handle, so it hasn't forgotten anything." |
| **Connection won't recover** | Tap ✕, Start fishing again | "Fresh token, and it resumes the same conversation." |
| **No network at all** | Switch to Sessions / Catch log | "Everything is local-first — tackle, sessions, reports all work offline. Writes queue and sync." |
| **Weather lookup fails** | Keep going | "It says it couldn't get conditions rather than inventing them. That's the rule: never fabricate environmental data." |
| **Camera won't start** | Carry on voice-only | "Camera is optional by design — and off by default, because video frames are the main cost driver." |
| **Audio focus stolen by a call** | Decline, re-enter Fishing Mode | "We request audio focus and surface the loss instead of talking over your call." |
| **Mic permission prompt appears** | Grant it | "Microphone is the one hard requirement; camera and location are genuinely optional." |
| **Total Gemini outage** | Settings → Demo mode | Walk the scenario list and the backend logs; the architecture is the story. |

## Timing

| Segment | Target |
|---|---|
| Intro | 0:30 |
| 1–2 voice + barge-in | 1:20 |
| 3–4 vision | 1:45 |
| 5–6 session + catch | 1:30 |
| 7 memory | 0:45 |
| 8 refusal | 0:40 |
| Close | 0:30 |
| **Total** | **~7:00** |

Running short? Drop scenario 5. Running long? Drop scenario 2 — but keep 7 and
8, which are the two that distinguish this from a demo-ware voice assistant.
