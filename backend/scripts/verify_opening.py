#!/usr/bin/env python
"""Does the assistant actually open the conversation correctly?

Everything else about the session opening is already pinned by unit tests: that
the client sends its position, that the backend coarsens and reverse-geocodes
it, that two turns are primed with requestReply. What no test can assert is the
part that matters to the angler -- whether gemini-3.8-live, given that brief,
*speaks first*, names the spot, reads the clock and asks to see the water, or
whether it still opens by asking where they are.

So this sends the exact two turns `FishingModeController.primeFishingContext`
builds, to the real service, with the real system instruction and the real tool
declarations, and prints what comes back.

Tool calls are answered from the real services (Nominatim, Open-Meteo), not
from canned fixtures. A fixture would let the model look right while being fed
something the product would never produce.
"""

from __future__ import annotations

import asyncio
import os
import sys
from datetime import datetime
from typing import Any

# Make `app` importable when run from the repo root or from backend/.
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from app.config import get_settings  # noqa: E402
from app.services.geocoding import distance_km, get_geocoding_provider  # noqa: E402
from app.services.geocoding import resolve as geo_resolve  # noqa: E402
from app.services.live_tokens import build_live_config  # noqa: E402
from app.services.weather import get_weather_provider  # noqa: E402

GREEN, RED, YELLOW, DIM, BOLD, RESET = (
    "\033[32m",
    "\033[31m",
    "\033[33m",
    "\033[2m",
    "\033[1m",
    "\033[0m",
)

# Bostanlı, İzmir -- coarsened exactly as the consent rule would leave it.
LAT, LON = 38.46, 27.09
PLACE, REGION = "Bostanlı Mahallesi", "İzmir"

#: Where the angler is standing, for the inland case: Bornova, ~12 km from the
#: water, which is where the marine grid stops reporting a sea state.
INLAND_PLACE, INLAND_REGION = "Bornova", "İzmir"


def prime_turns(language: str, at_water: bool = True) -> tuple[str, str]:
    """A literal port of primeFishingContext(), so this tests that, not a paraphrase."""
    place = PLACE if at_water else INLAND_PLACE
    region = REGION if at_water else INLAND_REGION
    turkish = language.startswith("tr")
    now = datetime.now()
    clock = now.strftime("%H:%M")
    day_en = now.strftime("%A")
    day_tr = {
        "Monday": "Pazartesi",
        "Tuesday": "Salı",
        "Wednesday": "Çarşamba",
        "Thursday": "Perşembe",
        "Friday": "Cuma",
        "Saturday": "Cumartesi",
        "Sunday": "Pazar",
    }[day_en]

    if turkish:
        facts = (
            f"[Oturum bağlamı] Şu an {day_tr}, saat {clock} (balıkçının telefonundaki "
            f"yerel saat). Balıkçı şu anda {place} ({region}) civarında. "
            + (
                "Bulunduğu noktada deniz durumu var; kıyıda olması muhtemel. "
                if at_water
                else "Bulunduğu noktada deniz durumu yok; iç bölgede, sudan uzakta. "
            )
            + "Adı Deniz. "
            "Kayıtlı 7 oturum, 12 av var. Sık kullandığı sahteler: beyaz jig, "
            "gümüş kaşık. Detay için araçları kullan; bu özeti kullanıcıya okuma."
        )
        closing = (
            "sus ve onun konuşmasını bekle. Ne yapacağını önermiyorsun, takım adı "
            "söylemiyorsun, suyu göstermesini istemiyorsun, kamerayı açmıyorsun."
            if at_water
            else "sudan uzakta olduğunu söyleyip nerede avlanmayı düşündüğünü "
            "sor; suyu göstermesini isteme."
        )
        directive = (
            "[Başla] Şimdi sen konuşmaya başla. Nerede olduğunu sorma — yukarıda "
            "yazıyor. Yeri ve saati kullanarak selam ver, get_current_conditions "
            f"çağırıp kısa bir tavır al, sonra {closing} En fazla iki üç cümle."
        )
    else:
        facts = (
            f"[Session context] It is {day_en}, {clock} local time on the angler's "
            f"phone. The angler is at {place} ({region}) right now. "
            + (
                "There is a sea state at their position, so they are plausibly on the coast. "
                if at_water
                else "There is no sea state at their position: they are inland, "
                "away from the water. "
            )
            + "Their name is Deniz. 7 recorded sessions, 12 catches. Their go-to "
            "lures: white jig, silver spoon. Use the tools for detail; never read "
            "this summary out."
        )
        closing = (
            "stop and wait for them. Do not propose what to do, do not name any "
            "tackle, do not ask to see the water and do not open the camera."
            if at_water
            else "say they are away from the water and ask where they are "
            "planning to fish; do not ask to see anything."
        )
        directive = (
            "[Begin] Speak first, now. Do not ask where they are — it is above. "
            "Greet them using the spot and the time, call get_current_conditions "
            f"and give a short opinion, then {closing} Two or three sentences at most."
        )
    return facts, directive


async def answer_tool(
    name: str, args: dict[str, Any], language: str, trace: dict[str, Any]
) -> dict[str, Any]:
    """Run the real service behind a tool call, mirroring registry.py's handlers.

    `trace` records what geocoding returned. The checks need that: whether
    reporting conditions for another spot is correct depends on whether the
    name resolved to one place or to several, and matching the model's
    wording instead ("which one?" / "do you mean" / "are you asking about")
    only tests the phrase list.
    """
    if name == "find_place":
        candidates = await get_geocoding_provider().search(args.get("name", ""))
        resolution = geo_resolve(candidates, (LAT, LON))
        if resolution.empty:
            trace["lookup_empty"] = True
            return {"error": "place_not_found", "searched_for": args.get("name")}
        if resolution.ambiguous:
            trace["lookup_ambiguous"] = True
            return {
                "ambiguous": True,
                "searched_for": args.get("name"),
                "options": [p.to_payload() for p in resolution.candidates],
            }
        place = resolution.place
        assert place is not None  # resolve() returns one unless empty or ambiguous
        trace["lookup_resolved"] = True
        return {
            "place": place.to_payload(),
            "km_from_user": round(distance_km(LAT, LON, place.latitude, place.longitude), 1),
            "instruction_to_model": (
                "Use this latitude and longitude as the location argument for "
                "get_current_conditions. Refer to the spot by its name."
            ),
        }

    if name in ("get_current_conditions", "get_sun_times"):
        lat = args.get("latitude") or LAT
        lon = args.get("longitude") or LON
        if name == "get_sun_times":
            return (await get_weather_provider().sun_times(lat, lon, None)).to_tool_payload()
        snapshot = await get_weather_provider().current(lat, lon)
        return snapshot.to_tool_payload(language)

    if name == "set_camera":
        # A device tool: on a phone the client intercepts this and answers only
        # once a frame has gone out. There is no camera here, so this stands in
        # for the client's success payload -- copied from
        # FishingModeController.applyCameraRequest so the model is told the same
        # thing it would be told on the device. Answering it as "no data" makes
        # the model add "I can't see anything" to its opening, which is correct
        # behaviour for a broken camera but not what this script is measuring.
        trace["camera_requested"] = True
        if args.get("enabled"):
            return {
                "camera": "on",
                "instruction_to_model": (
                    "You can see now, and a frame has already been sent. Describe what "
                    "is actually in it, separating what you observe from what you infer."
                ),
            }
        return {
            "camera": "off",
            "instruction_to_model": (
                "The camera is off and you can no longer see. Do not describe anything "
                "visual from now on."
            ),
        }

    # Anything else: say plainly that there is nothing, rather than invent it.
    return {
        "empty": True,
        "instruction_to_model": "You have no data for this. Do not assert any.",
    }


async def drain(session, types_mod, language: str, trace: dict[str, Any]) -> dict[str, Any]:
    """One turn. receive() ends at turnComplete; a tool call ends the turn too."""
    out: dict[str, Any] = {"audio": 0, "text": [], "calls": [], "args": []}
    async for message in session.receive():
        content = getattr(message, "server_content", None)
        if content is not None:
            turn = getattr(content, "model_turn", None)
            for part in getattr(turn, "parts", None) or []:
                inline = getattr(part, "inline_data", None)
                if inline is not None and inline.data:
                    out["audio"] += len(inline.data)
            spoken = getattr(content, "output_transcription", None)
            if spoken is not None and getattr(spoken, "text", None):
                out["text"].append(spoken.text)

        call = getattr(message, "tool_call", None)
        if call is not None and getattr(call, "function_calls", None):
            responses = []
            for fc in call.function_calls:
                args = dict(fc.args or {})
                out["calls"].append(fc.name)
                out["args"].append(args)
                payload = await answer_tool(fc.name, args, language, trace)
                print(f"    {DIM}-> {fc.name}({args}){RESET}")
                print(f"    {DIM}   {str(payload)[:300]}{RESET}")
                responses.append(
                    types_mod.FunctionResponse(id=fc.id, name=fc.name, response=payload)
                )
            await session.send_tool_response(function_responses=responses)
    return out


async def run(language: str, follow_up: str | None, at_water: bool = True) -> dict[str, Any]:
    from google import genai
    from google.genai import types

    settings = get_settings()
    config_dict = build_live_config(settings, language=language)
    config = types.LiveConnectConfig.model_validate(config_dict)
    client = genai.Client(
        api_key=settings.gemini_api_key,
        http_options=types.HttpOptions(api_version=settings.gemini_api_version),
    )

    facts, directive = prime_turns(language, at_water)
    said: list[str] = []
    all_calls: list[str] = []
    opening_trace: dict[str, Any] = {}
    follow_trace: dict[str, Any] = {}

    async with client.aio.live.connect(model=settings.gemini_live_model, config=config) as session:
        # Exactly what primeContext(turns, requestReply = true) puts on the wire.
        await session.send_client_content(
            turns=[
                types.Content(role="user", parts=[types.Part(text=facts)]),
                types.Content(role="user", parts=[types.Part(text=directive)]),
            ],
            turn_complete=True,
        )

        # The opening may take several turns: each tool call ends one.
        for _ in range(4):
            turn = await drain(session, types, language, opening_trace)
            said.extend(turn["text"])
            all_calls.extend(turn["calls"])
            if not turn["calls"]:
                break

        opening = "".join(said).strip()
        result: dict[str, Any] = {
            "opening": opening,
            "opening_calls": list(all_calls),
        }

        if follow_up:
            await session.send_client_content(
                turns=[types.Content(role="user", parts=[types.Part(text=follow_up)])],
                turn_complete=True,
            )
            said2: list[str] = []
            calls2: list[str] = []
            for _ in range(4):
                turn = await drain(session, types, language, follow_trace)
                said2.extend(turn["text"])
                calls2.extend(turn["calls"])
                if not turn["calls"]:
                    break
            result["follow_up"] = "".join(said2).strip()
            result["follow_up_calls"] = calls2
            result["follow_up_trace"] = follow_trace

    return result


async def main() -> int:
    settings = get_settings()
    if not settings.gemini_api_key:
        print(f"{RED}GEMINI_API_KEY is not set.{RESET}")
        return 2

    print(f"\n{'=' * 72}")
    print("  Session opening — against the real Gemini Live API")
    print(f"{'=' * 72}")
    print(f"  model    : {settings.gemini_live_model}")
    print(f"  spot     : {PLACE} ({REGION})  {LAT},{LON}")
    print("  asking   : a second spot, to prove it is not locked here")
    print(f"{'=' * 72}\n")

    failures = 0
    for language, follow_up in (
        ("tr", "Kilyos'ta hava nasıl, oradan balık tutulur mu şimdi?"),
        ("en", "How's the weather over at Çeşme right now?"),
    ):
        print(f"{BOLD}[{language}]{RESET} priming two turns, requestReply=true")
        try:
            out = await run(language, follow_up, at_water=True)
        except Exception as exc:  # noqa: BLE001
            print(f"  {RED}session failed: {type(exc).__name__}: {exc}{RESET}\n")
            failures += 1
            continue

        opening = out["opening"]
        print(f"\n  {BOLD}opening{RESET} (tools: {out['opening_calls'] or 'none'})")
        print(f"  {GREEN}“{opening}”{RESET}\n")

        lowered = opening.lower()
        checks = [
            ("spoke first at all", bool(opening)),
            (
                "named the spot",
                "bostanlı" in lowered or "bostanli" in lowered or "izmir" in lowered,
            ),
            ("called get_current_conditions", "get_current_conditions" in out["opening_calls"]),
            (
                # It was handed coordinates; looking its own spot up costs a
                # round trip on the turn where the wait is most audible.
                "did NOT look up its own spot",
                "find_place" not in out["opening_calls"],
            ),
            (
                # The opening must not switch the camera on by itself, before
                # the angler has said anything.
                "did NOT open the camera",
                "set_camera" not in out["opening_calls"],
            ),
            (
                # It used to end by asking to see the water, and that was
                # wrong in half of all sessions: the same angler opens this
                # app at the kitchen table to plan tomorrow. The opening
                # proves it knows the place and the weather, then stops, and
                # the angler's first sentence says which situation it is.
                "did NOT propose an action or ask to see anything",
                not any(
                    p in lowered
                    for p in (
                        "göster",
                        "gösterir misin",
                        "kamera",
                        "show me",
                        "let me see",
                        "point the camera",
                        "point your camera",
                    )
                ),
            ),
            (
                # Nothing is in this angler's inventory in this run, so any
                # tackle named here was invented.
                "did NOT name tackle",
                not any(
                    p in lowered
                    for p in (
                        "minnow",
                        "jighead",
                        "jig head",
                        "soft plastic",
                        "silikon",
                        "kaşık",
                        "spoon",
                        "sahte",
                        "lure",
                    )
                ),
            ),
            (
                "did NOT ask where they are",
                not any(
                    p in lowered
                    for p in (
                        "nerede",
                        "neresi",
                        "where are you",
                        "where will you",
                        "which spot",
                        "hangi noktada",
                    )
                ),
            ),
        ]
        for label, ok in checks:
            mark = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
            print(f"    {mark}  {label}")
            failures += 0 if ok else 1

        if out.get("follow_up"):
            print(f"\n  {BOLD}other spot{RESET} (tools: {out['follow_up_calls'] or 'none'})")
            print(f"  {GREEN}“{out['follow_up']}”{RESET}\n")
            f_low = out["follow_up"].lower()
            refused = any(
                p in f_low
                for p in (
                    "bakamıyorum",
                    "kontrol edemiyorum",
                    "can't check",
                    "cannot check",
                    "unable to check",
                    "erişemiyorum",
                    "don't have access",
                )
            )
            # What counts as correct here depends on what geocoding returned,
            # so the expectation is taken from the trace rather than from the
            # model's wording. Both outcomes are real product behaviour:
            # a name that resolves must be answered, and a name that is shared
            # by places hundreds of kilometres apart must be asked about --
            # reporting a sea state for a landlocked namesake is the failure
            # the ranking exists to prevent.
            trace = out.get("follow_up_trace", {})
            reported = "get_current_conditions" in out["follow_up_calls"]
            if trace.get("lookup_ambiguous"):
                expectation = ("asked instead of guessing (lookup was ambiguous)", not reported)
            else:
                expectation = ("reported conditions for the other spot", reported)

            for label, ok in [
                ("called find_place for the other spot", "find_place" in out["follow_up_calls"]),
                expectation,
                ("did NOT claim it cannot check", not refused),
            ]:
                mark = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
                print(f"    {mark}  {label}")
                failures += 0 if ok else 1
        print()

    # Inland: the angler is at home, not at the water. "Show me the water"
    # is the wrong closing move and the greeting has to notice.
    for language in ("tr", "en"):
        print(f"{BOLD}[{language}] inland{RESET} — no sea state at their position")
        try:
            out = await run(language, None, at_water=False)
        except Exception as exc:  # noqa: BLE001
            print(f"  {RED}session failed: {type(exc).__name__}: {exc}{RESET}\n")
            failures += 1
            continue

        opening = out["opening"]
        print(f"\n  {GREEN}“{opening}”{RESET}\n")
        lowered = opening.lower()
        asked_to_see = any(
            p in lowered for p in ("göster", "show me", "let me see", "point the camera", "kamera")
        )
        asked_where = any(
            p in lowered
            for p in (
                "nerede",
                "nereye",
                "where are you planning",
                "where do you plan",
                "where are you going",
                "whereabouts",
                "hangi",
            )
        )
        for label, ok in [
            ("named the inland spot", "bornova" in lowered),
            ("did NOT ask to see the water", not asked_to_see),
            ("asked where they plan to fish", asked_where),
            ("did not open the camera", "set_camera" not in out["opening_calls"]),
        ]:
            mark = f"{GREEN}PASS{RESET}" if ok else f"{RED}FAIL{RESET}"
            print(f"    {mark}  {label}")
            failures += 0 if ok else 1
        print()

    print(f"{'=' * 72}")
    verdict = (
        f"{GREEN}all checks passed{RESET}"
        if not failures
        else f"{RED}{failures} check(s) failed{RESET}"
    )
    print(f"  {verdict}")
    print(f"{'=' * 72}\n")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
