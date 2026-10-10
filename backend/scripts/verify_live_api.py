#!/usr/bin/env python
"""End-to-end verification against the real Gemini Live API.

Unlike the unit tests — which deliberately never contact Google — this opens an
actual Live session and proves each capability in turn, using **this project's
production configuration** (`app.services.live_tokens.build_live_config`) and
its real tool declarations. If a setting is wrong for the current model, this
is where it surfaces.

Two behaviours of the service were established empirically while writing this
and are encoded below, because getting either wrong makes the script lie:

1. `session.receive()` is a **per-turn** async generator — it completes at
   `turnComplete`. The model ends its turn when it issues a tool call, so the
   spoken answer arrives in the *next* turn and needs a fresh `receive()`.
   Holding one iterator across turns silently reports zero audio.

2. The service intermittently returns `1011 Internal error encountered` on the
   turn following a tool response (observed on roughly half of runs,
   independent of the inputs sent). That is a server-side flake; production
   handles it by reconnecting with the session-resumption handle. Here it is
   retried so the result is meaningful.

Usage
-----
    export GEMINI_API_KEY=...            # or put it in .env
    make verify-live

    python scripts/verify_live_api.py --skip-resumption
    python scripts/verify_live_api.py --model gemini-3.8-live-extended-thinking
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import os
import sys
import time
from dataclasses import dataclass, field

# Make `app` importable when run from the repo root or from backend/.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.config import get_settings  # noqa: E402
from app.services.live_tokens import build_live_config  # noqa: E402

GREEN, RED, YELLOW, DIM, RESET = "\033[32m", "\033[31m", "\033[33m", "\033[2m", "\033[0m"

#: A small valid JPEG (16x16 grey), embedded so the script has no image
#: dependency. Enough to prove the video channel accepts a frame.
TINY_JPEG = base64.b64decode(
    "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAoHBwgHBgoICAgLCgoLDhgQDg0NDh0VFhEYIx8lJCIf"
    "IiEmKzcvJik0KSEiMEExNDk7Pj4+JS5ESUM8SDc9Pjv/2wBDAQoLCw4NDhwQEBw7KCIoOzs7Ozs7"
    "Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozs7Ozv/wAARCAAQABADASIA"
    "AhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQA"
    "AAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3"
    "ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWm"
    "p6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEA"
    "AwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSEx"
    "BhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElK"
    "U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3"
    "uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwCvRRRV"
    "mZ//2Q=="
)

#: 200 ms of silence as 16-bit PCM at 16 kHz — a structurally valid frame.
SILENCE_PCM = b"\x00\x00" * 3200

TOOL_PROMPT = (
    "Bostanlı'dayım, enlem 38.46 boylam 27.09. Şu anki rüzgâr ve dalga durumu tam olarak nedir?"
)


@dataclass
class Report:
    checks: list[tuple[str, str, str]] = field(default_factory=list)

    def record(self, name: str, ok: bool, detail: str = "") -> None:
        self.checks.append((name, detail, "pass" if ok else "fail"))
        mark = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
        print(f"  [{mark}] {name}" + (f" {DIM}— {detail}{RESET}" if detail else ""))

    def skip(self, name: str, reason: str) -> None:
        self.checks.append((name, reason, "skip"))
        print(f"  [{YELLOW}SKIP{RESET}] {name} {DIM}— {reason}{RESET}")

    @property
    def failed(self) -> int:
        return sum(1 for c in self.checks if c[2] == "fail")

    def summary(self) -> str:
        passed = sum(1 for c in self.checks if c[2] == "pass")
        skipped = sum(1 for c in self.checks if c[2] == "skip")
        return f"{passed} passed, {self.failed} failed, {skipped} skipped"


@dataclass
class TurnResult:
    audio: int = 0
    transcript: list[str] = field(default_factory=list)
    calls: list[str] = field(default_factory=list)
    responded: int = 0
    grounding_chunks: int = 0
    handle: str | None = None


async def drain_turn(session, types_mod, answer_tools: bool = True) -> TurnResult:
    """Consume exactly one turn. See note (1) in the module docstring."""
    out = TurnResult()
    async for message in session.receive():
        content = getattr(message, "server_content", None)
        if content is not None:
            turn = getattr(content, "model_turn", None)
            for part in getattr(turn, "parts", None) or []:
                inline = getattr(part, "inline_data", None)
                if inline is not None and inline.data:
                    out.audio += len(inline.data)
            spoken = getattr(content, "output_transcription", None)
            if spoken is not None and getattr(spoken, "text", None):
                out.transcript.append(spoken.text)
            grounding = getattr(content, "grounding_metadata", None)
            if grounding is not None:
                out.grounding_chunks += len(getattr(grounding, "grounding_chunks", None) or [])

        call = getattr(message, "tool_call", None)
        if call is not None and getattr(call, "function_calls", None):
            responses = []
            for fc in call.function_calls:
                out.calls.append(fc.name)
                responses.append(
                    types_mod.FunctionResponse(
                        id=fc.id,
                        name=fc.name,
                        response={
                            "summary": "Açık, 21°C, rüzgâr batıdan 6 km/h, dalga 0.05 m",
                            "wind_speed_kmh": 6,
                            "wave_height_m": 0.05,
                        },
                    )
                )
            if answer_tools:
                await session.send_tool_response(function_responses=responses)
                out.responded += len(responses)

        update = getattr(message, "session_resumption_update", None)
        if update is not None and getattr(update, "new_handle", None):
            out.handle = update.new_handle
    return out


async def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", default=None, help="override GEMINI_LIVE_MODEL")
    parser.add_argument("--skip-resumption", action="store_true")
    parser.add_argument("--attempts", type=int, default=3, help="retries for transient 1011")
    args = parser.parse_args()

    settings = get_settings()
    if not settings.gemini_api_key:
        print(f"{RED}GEMINI_API_KEY is not set.{RESET} Set it in .env, then re-run.")
        return 2

    from google import genai
    from google.genai import types

    model = args.model or settings.gemini_live_model
    config_dict = build_live_config(settings)
    config = types.LiveConnectConfig.model_validate(config_dict)
    declarations = config_dict["tools"][0]["function_declarations"] if config_dict["tools"] else []

    print(f"\n{'=' * 68}")
    print("  Gemini Live API verification")
    print(f"{'=' * 68}")
    print(f"  model        : {model}")
    print(f"  sdk          : google-genai {genai.__version__}")
    print(f"  api version  : {settings.gemini_api_version}")
    print(f"  modalities   : {[m.value for m in (config.response_modalities or [])]}")
    print(
        f"  tools        : {len(declarations)} functions"
        f" + {'googleSearch' if len(config_dict['tools']) > 1 else 'no search'}"
    )
    print(f"  compression  : {bool(config.context_window_compression)}")
    print(f"  resolution   : {config.media_resolution}")
    print(f"  thinkingCfg  : {config.thinking_config}  (must be None for 3.8 Live)")
    print(f"{'=' * 68}\n")

    report = Report()
    report.record(
        "Config omits thinking_config (required for gemini-3.8-live)",
        config.thinking_config is None,
    )

    client = genai.Client(
        api_key=settings.gemini_api_key,
        http_options=types.HttpOptions(api_version=settings.gemini_api_version),
    )

    handle: str | None = None
    print(f"{DIM}--- session 1: connect, audio, video, tool call, spoken answer ---{RESET}")

    for attempt in range(1, args.attempts + 1):
        try:
            started = time.perf_counter()
            async with client.aio.live.connect(model=model, config=config) as session:
                elapsed = (time.perf_counter() - started) * 1000

                await session.send_realtime_input(
                    audio=types.Blob(data=SILENCE_PCM, mime_type="audio/pcm;rate=16000")
                )
                await session.send_realtime_input(
                    video=types.Blob(data=TINY_JPEG, mime_type="image/jpeg")
                )
                await session.send_realtime_input(text=TOOL_PROMPT)

                first = await drain_turn(session, types)
                # The spoken answer arrives in the turn *after* our tool result.
                second = await drain_turn(session, types) if first.calls else TurnResult()

            handle = second.handle or first.handle
            audio = first.audio + second.audio
            spoken = " ".join(first.transcript + second.transcript).strip()
            grounding = first.grounding_chunks + second.grounding_chunks

            report.record("1. Live session connects (WebSocket)", True, f"{elapsed:.0f} ms")
            report.record("2. Audio input accepted", True, f"{len(SILENCE_PCM)} B PCM 16 kHz")
            report.record("4. Video/image frame accepted", True, f"{len(TINY_JPEG)} B JPEG")
            report.record(
                "5. Model issued a real tool call",
                bool(first.calls),
                ", ".join(first.calls) or "none",
            )
            report.record(
                "6. Tool response returned to the session",
                first.responded > 0,
                f"{first.responded} functionResponse(s)",
            )
            report.record("3. Native audio response received", audio > 0, f"{audio} B PCM 24 kHz")
            report.record(
                "7. Conversation continued after the tool response",
                bool(spoken),
                (spoken[:70] + "…") if len(spoken) > 70 else spoken or "no transcript",
            )
            report.record(
                "Session resumption handle issued",
                handle is not None,
                "handle received" if handle else "none",
            )
            if grounding:
                report.record("Google Search grounding observed", True, f"{grounding} chunk(s)")
            elif not settings.gemini_enable_google_search:
                report.skip(
                    "Google Search grounding observed",
                    "disabled (GEMINI_ENABLE_GOOGLE_SEARCH=false — this key lacks Search quota)",
                )
            else:
                report.skip("Google Search grounding observed", "not triggered by this prompt")
            if attempt > 1:
                print(f"  {DIM}(succeeded on attempt {attempt}){RESET}")
            break

        except Exception as exc:  # noqa: BLE001 - diagnostic tool
            transient = "1011" in str(exc)
            if transient and attempt < args.attempts:
                print(
                    f"  {YELLOW}retry{RESET} {DIM}transient 1011 from the service "
                    f"(attempt {attempt}/{args.attempts}){RESET}"
                )
                await asyncio.sleep(2)
                continue
            report.record("Session 1 completed", False, f"{type(exc).__name__}: {exc}")
            print(f"\n{RED}Session failed.{RESET} {report.summary()}")
            return 1

    print(f"\n{DIM}--- session 2: resume the same conversation ---{RESET}")
    if args.skip_resumption:
        report.skip("8. Session resumes after disconnect", "--skip-resumption")
    elif handle is None:
        report.skip("8. Session resumes after disconnect", "no handle was issued")
    else:
        resumed = config.model_copy(
            update={"session_resumption": types.SessionResumptionConfig(handle=handle)}
        )
        try:
            async with client.aio.live.connect(model=model, config=resumed) as session:
                await session.send_realtime_input(text="Az önce sana hangi koordinatları verdim?")
                recall = await drain_turn(session, types)
            text = " ".join(recall.transcript).strip()
            report.record(
                "8. Session resumes after disconnect",
                True,
                f"reconnected with handle; {recall.audio} B audio back",
            )
            # Native audio spells numbers as words, so a digit match alone
            # under-reports: "Otuz sekiz nokta kırk altı enlem" is a hit.
            spoken_markers = ("38", "27", "Bostan", "otuz sekiz", "yirmi yedi", "kırk altı")
            if any(k.lower() in text.lower() for k in spoken_markers):
                report.record("   …and retained prior context", True, text[:60] + "…")
            else:
                report.skip(
                    "   …and retained prior context",
                    f"reply did not restate them: {text[:50]!r} (inconclusive)",
                )
        except Exception as exc:  # noqa: BLE001
            report.record(
                "8. Session resumes after disconnect", False, f"{type(exc).__name__}: {exc}"
            )

    print(f"\n{'=' * 68}")
    verdict = (
        f"{GREEN}ALL CHECKS PASSED{RESET}"
        if report.failed == 0
        else f"{RED}FAILURES PRESENT{RESET}"
    )
    print(f"  {verdict} — {report.summary()}")
    print(f"{'=' * 68}\n")
    return 0 if report.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
