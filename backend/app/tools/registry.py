"""Typed tool registry: validate -> execute -> audit.

This is the "Application Tool Layer" in the prompt's flow:

    Gemini Live -> functionCall -> [this layer] -> services/DB/external APIs
                -> functionResponse -> Gemini Live -> spoken answer

Invariants
----------
* **Nothing executes unvalidated.** Every call goes through the tool's Pydantic
  model first; a failure returns a structured error the model can act on
  instead of raising.
* **No model-generated code or SQL is ever executed.** Tools are a fixed,
  hand-written set; the model only supplies arguments.
* **Every call is audited** to `tool_executions` with its validated arguments,
  outcome and duration.
* **A tool never raises into the caller.** Errors become `ToolResult(ok=False)`
  with a user-safe message, because an exception mid-conversation would drop
  the Live session instead of letting the assistant apologise and continue.
"""

from __future__ import annotations

import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from pydantic import BaseModel, ValidationError
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import FishMateError
from app.core.location import apply_location_precision
from app.core.observability import get_logger, metrics, round_coords
from app.db.models import LocationPrecision, ToolExecution, User
from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.repositories import tackle as tackle_repo
from app.services import species as species_catalogue
from app.services.geocoding import distance_km as geo_distance_km
from app.services.geocoding import get_geocoding_provider
from app.services.geocoding import resolve as geo_resolve
from app.services.memory import get_memory_service
from app.services.recommendation import get_recommendation_service
from app.services.regulations import get_regulation_service
from app.services.weather import ConditionsSnapshot, get_weather_provider
from app.tools import declarations as decl
from app.tools import schemas as sch

log = get_logger("tools")


@dataclass
class ToolContext:
    """Everything a tool may touch. Scoped to one authenticated user."""

    db: AsyncSession
    user: User
    language: str = "en"
    #: Correlates this execution with a device-side Live session.
    live_session_ref: str | None = None
    #: Device-supplied position for this turn, already honouring the user's
    #: location-precision consent (see api/v1/tools.py).
    latitude: float | None = None
    longitude: float | None = None

    @property
    def user_id(self) -> str:
        return self.user.id


@dataclass
class ToolResult:
    ok: bool
    payload: dict[str, Any]
    error_code: str | None = None

    def to_function_response(self) -> dict[str, Any]:
        """The `response` object handed back in `toolResponse.functionResponses`."""
        if self.ok:
            return self.payload
        return {"error": self.error_code or "tool_error", **self.payload}


@dataclass
class ToolSpec:
    name: str
    declaration: dict[str, Any]
    args_model: type[BaseModel]
    handler: Callable[[ToolContext, Any], Awaitable[dict[str, Any]]]
    #: Tools that change state are logged at info and shown in the UI's
    #: activity feed; read-only ones are not.
    mutates: bool = False
    description: str = field(default="")

    def __post_init__(self) -> None:
        if not self.description:
            self.description = self.declaration.get("description", "")


# ---------------------------------------------------------------------------
# Location resolution
# ---------------------------------------------------------------------------
async def _resolve_location(
    ctx: ToolContext, lat: float | None, lon: float | None
) -> tuple[float | None, float | None, str | None]:
    """Decide which coordinates to use, honouring the user's consent setting.

    Order: explicit tool argument -> this turn's device position -> the active
    session -> the user's most recent spot. Returns the source so a tool can
    tell the model the position is approximate.
    """
    prefs = ctx.user.preferences
    precision = prefs.location_precision if prefs else LocationPrecision.APPROXIMATE.value
    if precision == LocationPrecision.NONE.value:
        # The user declined location entirely; only a manually named spot may
        # supply coordinates.
        spots = await session_repo.list_spots(ctx.db, ctx.user_id)
        for spot in spots:
            if spot.latitude is not None and spot.longitude is not None:
                return spot.latitude, spot.longitude, "saved_spot"
        return None, None, None

    if lat is not None and lon is not None:
        return lat, lon, "tool_argument"
    if ctx.latitude is not None and ctx.longitude is not None:
        return ctx.latitude, ctx.longitude, "device"

    active = await session_repo.get_active(ctx.db, ctx.user_id)
    if active is not None and active.latitude is not None and active.longitude is not None:
        return active.latitude, active.longitude, "active_session"

    spots = await session_repo.list_spots(ctx.db, ctx.user_id)
    for spot in spots:
        if spot.latitude is not None and spot.longitude is not None:
            return spot.latitude, spot.longitude, "saved_spot"
    return None, None, None


_NO_LOCATION = {
    "error": "no_location",
    "instruction_to_model": (
        "No location is available, so conditions cannot be retrieved. Ask the user "
        "where they are, or offer to help from what the camera shows. Do NOT guess "
        "the weather, wind or sea state."
    ),
}


async def _conditions_for(ctx: ToolContext, lat: float | None, lon: float | None):
    """(snapshot, error_payload) — exactly one is non-None."""
    lat, lon, source = await _resolve_location(ctx, lat, lon)
    if lat is None or lon is None:
        return None, dict(_NO_LOCATION)
    provider = get_weather_provider()
    snapshot = await provider.current(lat, lon)
    return snapshot, None if source != "saved_spot" else None


# ---------------------------------------------------------------------------
# Handlers
# ---------------------------------------------------------------------------
async def _h_current_conditions(
    ctx: ToolContext, args: sch.GetCurrentConditionsArgs
) -> dict[str, Any]:
    snapshot, error = await _conditions_for(ctx, args.latitude, args.longitude)
    if error:
        return error
    assert snapshot is not None
    return snapshot.to_tool_payload(ctx.language)


async def _h_find_place(ctx: ToolContext, args: sch.FindPlaceArgs) -> dict[str, Any]:
    """Resolve a spoken place name to coordinates, or ask which one was meant.

    The ranking is the interesting part and it lives in
    `services/geocoding.resolve`: candidates are re-sorted by distance from
    wherever the angler actually is, because a global relevance score has no
    idea which of five villages called Bostanlı is the one on the water they
    fish from. Where that still leaves a real tie, this returns options rather
    than a guess — reporting a sea state for a landlocked namesake would be
    the worst possible way to answer.
    """
    reference_lat, reference_lon, _ = await _resolve_location(ctx, None, None)
    reference = (
        (reference_lat, reference_lon)
        if reference_lat is not None and reference_lon is not None
        else None
    )

    # No country filter: the measured failure was never the country, it was
    # which same-named place, and proximity ranking answers that. Filtering
    # would only break an angler asking about the far side of a border.
    candidates = await get_geocoding_provider().search(args.name)
    resolution = geo_resolve(candidates, reference)

    if resolution.empty:
        return {
            "error": "place_not_found",
            "searched_for": args.name,
            "instruction_to_model": (
                "You could not find that place. Say so in one short sentence and ask "
                "for a nearby town or district to go on. Do NOT report conditions for "
                "a place you could not locate, and do not guess its coordinates."
            ),
        }

    if resolution.ambiguous:
        return {
            "ambiguous": True,
            "searched_for": args.name,
            "options": [p.to_payload() for p in resolution.candidates],
            "instruction_to_model": (
                "Several places share that name and they are far apart. Read out the "
                "regions and ask which one the user means, briefly. Do NOT pick one "
                "yourself and do NOT report any conditions until they answer."
            ),
        }

    place = resolution.place
    assert place is not None
    payload: dict[str, Any] = {
        "place": place.to_payload(),
        "instruction_to_model": (
            "Use this latitude and longitude as the location argument for "
            "get_current_conditions, get_conditions_forecast or get_sun_times. "
            "Refer to the spot by its name, not its coordinates."
        ),
    }
    if reference is not None:
        payload["km_from_user"] = round(
            geo_distance_km(reference[0], reference[1], place.latitude, place.longitude), 1
        )
    return payload


async def _h_forecast(ctx: ToolContext, args: sch.GetConditionsForecastArgs) -> dict[str, Any]:
    lat, lon, _ = await _resolve_location(ctx, args.latitude, args.longitude)
    if lat is None or lon is None:
        return dict(_NO_LOCATION)
    points = await get_weather_provider().forecast(lat, lon, args.hours)
    return {
        "hours": len(points),
        "hourly": [
            {
                k: v
                for k, v in {
                    "time": p.time,
                    "wind_speed_kmh": p.wind_speed_kmh,
                    "wind_gust_kmh": p.wind_gust_kmh,
                    "wave_height_m": p.wave_height_m,
                    "precipitation_mm": p.precipitation_mm,
                    "air_temperature_c": p.air_temp_c,
                }.items()
                if v is not None
            }
            for p in points
        ],
    }


async def _h_sun_times(ctx: ToolContext, args: sch.GetSunTimesArgs) -> dict[str, Any]:
    lat, lon, _ = await _resolve_location(ctx, args.latitude, args.longitude)
    if lat is None or lon is None:
        return dict(_NO_LOCATION)
    times = await get_weather_provider().sun_times(lat, lon, args.on_date)
    return times.to_tool_payload()


async def _h_tackle(ctx: ToolContext, args: sch.GetTackleInventoryArgs) -> dict[str, Any]:
    items = await tackle_repo.list_for_user(
        ctx.db, ctx.user_id, category=args.category, fishing_style=args.fishing_style
    )
    if not items:
        return {
            "items": [],
            "instruction_to_model": (
                "The user's tackle inventory is empty for this query. Ask what they "
                "have with them. Do NOT recommend specific gear as if they owned it."
            ),
        }
    return {
        "total": len(items),
        "items": [
            {
                k: v
                for k, v in {
                    "id": i.id,
                    "name": i.name,
                    "category": i.category,
                    "brand": i.brand,
                    "weight_g": (i.weight_min_g if i.weight_min_g == i.weight_max_g else None),
                    "weight_range_g": (
                        f"{i.weight_min_g:g}-{i.weight_max_g:g}"
                        if i.weight_min_g is not None
                        and i.weight_max_g is not None
                        and i.weight_min_g != i.weight_max_g
                        else None
                    ),
                    "line_rating": i.line_rating,
                    "styles": i.fishing_styles or None,
                    "favourite": True if i.is_favourite else None,
                    "needs_user_confirmation": True if i.ai_identified else None,
                }.items()
                if v is not None
            }
            for i in items
        ],
    }


async def _h_recent_catches(ctx: ToolContext, args: sch.GetRecentCatchesArgs) -> dict[str, Any]:
    digest = await get_memory_service().catch_digest(
        ctx.db,
        ctx.user_id,
        species=args.species,
        limit=args.limit,
        days_back=args.days_back,
        language=ctx.language,
    )
    return digest.to_tool_payload(ctx.language)


async def _h_recent_sessions(ctx: ToolContext, args: sch.GetRecentSessionsArgs) -> dict[str, Any]:
    return await get_memory_service().session_digest(
        ctx.db, ctx.user_id, limit=args.limit, species=args.species, language=ctx.language
    )


async def _h_recommend(ctx: ToolContext, args: sch.RecommendSetupArgs) -> dict[str, Any]:
    snapshot: ConditionsSnapshot | None = None
    lat, lon, _ = await _resolve_location(ctx, args.latitude, args.longitude)
    if lat is not None and lon is not None:
        try:
            snapshot = await get_weather_provider().current(lat, lon)
        except FishMateError:
            # Advise anyway, but the engine lowers its confidence and the
            # payload says conditions were unavailable.
            log.warning("tools.recommend_without_conditions")

    target = args.target_species
    if not target:
        active = await session_repo.get_active(ctx.db, ctx.user_id)
        if active and active.target_species:
            target = active.target_species[0]
        elif ctx.user.preferences and ctx.user.preferences.default_target_species:
            target = ctx.user.preferences.default_target_species[0]

    recommendation = await get_recommendation_service().recommend(
        ctx.db,
        ctx.user_id,
        conditions=snapshot,
        target_species=target,
        fishing_style=args.fishing_style,
        notes=args.notes,
        language=ctx.language,
    )
    payload = recommendation.to_tool_payload(ctx.language)
    if snapshot is None:
        payload["conditions_unavailable"] = True
    return payload


async def _h_start_session(ctx: ToolContext, args: sch.StartFishingSessionArgs) -> dict[str, Any]:
    existing = await session_repo.get_active(ctx.db, ctx.user_id)
    if existing is not None:
        return {
            "already_active": True,
            "session_id": existing.id,
            "started_at": existing.started_at.isoformat(),
            "instruction_to_model": (
                "A session is already running — tell the user that instead of starting another."
            ),
        }

    lat, lon, _ = await _resolve_location(ctx, args.latitude, args.longitude)
    prefs = ctx.user.preferences
    precision = prefs.location_precision if prefs else LocationPrecision.APPROXIMATE.value
    # Honour the consent level at write time, not just at display time.
    lat, lon = apply_location_precision(precision, lat, lon)

    session = await session_repo.start(
        ctx.db,
        ctx.user_id,
        target_species=args.target_species or None,
        location_label=args.location_label,
        latitude=lat,
        longitude=lon,
        location_precision=precision if lat is not None else LocationPrecision.NONE.value,
        fishing_style=args.fishing_style,
    )

    conditions_summary = None
    if lat is not None and lon is not None:
        try:
            snapshot = await get_weather_provider().current(lat, lon)
            await session_repo.add_weather_snapshot(
                ctx.db,
                session.id,
                {
                    "captured_at": snapshot.observed_at,
                    "provider": snapshot.provider,
                    "latitude": lat,
                    "longitude": lon,
                    "air_temp_c": snapshot.air_temp_c,
                    "water_temp_c": snapshot.water_temp_c,
                    "wind_speed_kmh": snapshot.wind_speed_kmh,
                    "wind_gust_kmh": snapshot.wind_gust_kmh,
                    "wind_direction_deg": snapshot.wind_direction_deg,
                    "pressure_hpa": snapshot.pressure_hpa,
                    "cloud_cover_pct": snapshot.cloud_cover_pct,
                    "precipitation_mm": snapshot.precipitation_mm,
                    "wave_height_m": snapshot.wave_height_m,
                    "swell_period_s": snapshot.swell_period_s,
                    "summary": snapshot.describe(ctx.language),
                    "raw": None,
                },
            )
            conditions_summary = snapshot.describe(ctx.language)
        except FishMateError:
            log.warning("tools.session_started_without_weather", session_id=session.id)

    if args.location_label:
        await session_repo.upsert_spot(
            ctx.db,
            ctx.user_id,
            name=args.location_label,
            latitude=lat,
            longitude=lon,
            location_precision=precision,
        )

    payload: dict[str, Any] = {
        "session_id": session.id,
        "started_at": session.started_at.isoformat(),
        "target_species": [
            species_catalogue.label_for(species_catalogue.normalise(s), ctx.language, fallback=s)
            for s in (session.target_species or [])
        ],
    }
    if session.location_label:
        payload["location"] = session.location_label
    if conditions_summary:
        payload["conditions_at_start"] = conditions_summary
    else:
        payload["note"] = "Weather snapshot unavailable; the session still started."
    payload["instruction_to_model"] = "Confirm briefly in one sentence. Do not list fields."
    return payload


async def _h_finish_session(ctx: ToolContext, args: sch.FinishFishingSessionArgs) -> dict[str, Any]:
    active = await session_repo.get_active(ctx.db, ctx.user_id)
    if active is None:
        return {
            "no_active_session": True,
            "instruction_to_model": (
                "There is no running session to finish. Say so; do not invent one."
            ),
        }
    report = await get_memory_service().build_session_report(ctx.db, active, ctx.language)
    session = await session_repo.finish(
        ctx.db, ctx.user_id, active.id, notes=args.notes, summary=report
    )
    minutes = 0
    if session.ended_at:
        minutes = max(0, int((session.ended_at - session.started_at).total_seconds() // 60))
    return {
        "session_id": session.id,
        "duration_minutes": minutes,
        "catches": session.catch_count,
        "location": session.location_label,
        "report": report,
        "instruction_to_model": (
            "Read out only the headline numbers — duration and catch count — plus one "
            "closing remark. The full report is on screen; do not read it aloud."
        ),
    }


async def _h_log_catch(ctx: ToolContext, args: sch.LogCatchArgs) -> dict[str, Any]:
    active = await session_repo.get_active(ctx.db, ctx.user_id)
    species_key = species_catalogue.normalise(args.species)
    label = species_catalogue.label_for(species_key, ctx.language, fallback=args.species)

    lat = lon = None
    location_label = None
    weather_summary = None
    if active is not None:
        lat, lon = active.latitude, active.longitude
        location_label = active.location_label
        snapshot = await session_repo.latest_weather(ctx.db, active.id)
        if snapshot is not None:
            weather_summary = snapshot.summary

    catch = await catch_repo.create(
        ctx.db,
        ctx.user_id,
        session_id=active.id if active else None,
        species_key=species_key,
        species_label=label,
        ai_confidence=args.ai_confidence,
        ai_reasoning=args.ai_reasoning,
        # The model never gets to mark its own guess as user-confirmed; only
        # an explicit user action in the app does that.
        species_confirmed_by_user=args.ai_confidence is None,
        length_cm=args.length_cm,
        weight_g=args.weight_g,
        lure_label=args.lure_label,
        technique=args.technique,
        released=args.released,
        notes=args.notes,
        caught_at=datetime.now(UTC),
        latitude=lat,
        longitude=lon,
        location_label=location_label,
        weather_summary=weather_summary,
    )

    payload: dict[str, Any] = {
        "catch_id": catch.id,
        "species": label,
        "logged_at": catch.caught_at.isoformat(),
        "in_session": active.id if active else None,
        "session_catch_total": active.catch_count + 1 if active else None,
    }
    if args.ai_confidence is not None:
        payload["identification"] = "ai"
        payload["ai_confidence"] = args.ai_confidence
        payload["instruction_to_model"] = (
            "Confirm in one sentence, state the species as a likelihood rather than a "
            "fact, and mention that the user can correct it in the catch log."
        )
    else:
        payload["identification"] = "user_stated"
        payload["instruction_to_model"] = "Confirm in one short sentence."
    return payload


async def _h_add_observation(
    ctx: ToolContext, args: sch.AddSessionObservationArgs
) -> dict[str, Any]:
    active = await session_repo.get_active(ctx.db, ctx.user_id)
    if active is None:
        return {
            "no_active_session": True,
            "instruction_to_model": (
                "There is no running session, so this could not be recorded. Offer to start one."
            ),
        }
    observation = await session_repo.add_observation(
        ctx.db,
        active.id,
        kind=args.kind,
        text=args.text,
        from_ai=args.kind == "ai_observation",
    )
    return {
        "observation_id": observation.id,
        "session_id": active.id,
        "recorded_at": observation.observed_at.isoformat(),
        "instruction_to_model": "Acknowledge in a few words. Stay in the moment.",
    }


async def _h_regulations(ctx: ToolContext, args: sch.GetFishingRegulationsArgs) -> dict[str, Any]:
    answer = await get_regulation_service().lookup(
        region=args.region or "TR", species=args.species, language=ctx.language
    )
    return answer.to_tool_payload(ctx.language)


# ---------------------------------------------------------------------------
# Registry
# ---------------------------------------------------------------------------
class FishingToolRegistry:
    def __init__(self) -> None:
        self._specs: dict[str, ToolSpec] = {}
        for spec in (
            ToolSpec(
                "get_current_conditions",
                decl.GET_CURRENT_CONDITIONS,
                sch.GetCurrentConditionsArgs,
                _h_current_conditions,
            ),
            ToolSpec(
                "get_conditions_forecast",
                decl.GET_CONDITIONS_FORECAST,
                sch.GetConditionsForecastArgs,
                _h_forecast,
            ),
            ToolSpec("get_sun_times", decl.GET_SUN_TIMES, sch.GetSunTimesArgs, _h_sun_times),
            ToolSpec(
                "get_tackle_inventory",
                decl.GET_TACKLE_INVENTORY,
                sch.GetTackleInventoryArgs,
                _h_tackle,
            ),
            ToolSpec(
                "get_recent_catches",
                decl.GET_RECENT_CATCHES,
                sch.GetRecentCatchesArgs,
                _h_recent_catches,
            ),
            ToolSpec(
                "get_recent_sessions",
                decl.GET_RECENT_SESSIONS,
                sch.GetRecentSessionsArgs,
                _h_recent_sessions,
            ),
            ToolSpec("recommend_setup", decl.RECOMMEND_SETUP, sch.RecommendSetupArgs, _h_recommend),
            ToolSpec(
                "start_fishing_session",
                decl.START_FISHING_SESSION,
                sch.StartFishingSessionArgs,
                _h_start_session,
                mutates=True,
            ),
            ToolSpec(
                "finish_fishing_session",
                decl.FINISH_FISHING_SESSION,
                sch.FinishFishingSessionArgs,
                _h_finish_session,
                mutates=True,
            ),
            ToolSpec("log_catch", decl.LOG_CATCH, sch.LogCatchArgs, _h_log_catch, mutates=True),
            ToolSpec(
                "add_session_observation",
                decl.ADD_SESSION_OBSERVATION,
                sch.AddSessionObservationArgs,
                _h_add_observation,
                mutates=True,
            ),
            ToolSpec(
                "get_fishing_regulations",
                decl.GET_FISHING_REGULATIONS,
                sch.GetFishingRegulationsArgs,
                _h_regulations,
            ),
            ToolSpec(
                "find_place",
                decl.FIND_PLACE,
                sch.FindPlaceArgs,
                _h_find_place,
            ),
        ):
            self._specs[spec.name] = spec

        missing = set(decl.TOOL_NAMES) - set(self._specs) - decl.DEVICE_TOOLS
        if missing:  # a declared tool with no handler would 'work' then 500
            raise RuntimeError(f"Declared tools without handlers: {sorted(missing)}")

        # The mirror-image bug: a device tool that *does* have a server handler
        # would be answered quietly here, so the hardware it was supposed to act
        # on never moves and the model is told it did.
        overreach = decl.DEVICE_TOOLS & set(self._specs)
        if overreach:
            raise RuntimeError(f"Device tools must not have server handlers: {sorted(overreach)}")

    def names(self) -> tuple[str, ...]:
        return tuple(self._specs)

    def get(self, name: str) -> ToolSpec | None:
        return self._specs.get(name)

    async def execute(
        self, name: str, raw_args: dict[str, Any] | None, ctx: ToolContext
    ) -> ToolResult:
        spec = self._specs.get(name)
        if spec is None and name in decl.DEVICE_TOOLS:
            # Declared to the model, executed on the device. Reaching here means
            # a client did not intercept it, and saying "unknown tool" would send
            # whoever debugs that to the wrong file.
            log.warning("tools.device_tool_posted", tool=name)
            metrics.incr("tools.device_tool_posted", tool=name)
            return ToolResult(
                ok=False,
                error_code="device_tool",
                payload={
                    "message": (
                        f"'{name}' runs on the angler's device, not on the server. "
                        "The client should have handled this call itself."
                    ),
                    "instruction_to_model": (
                        "Say in one short sentence that you cannot do that right now, "
                        "then carry on with what you already know. Do not retry it."
                    ),
                },
            )
        if spec is None:
            log.warning("tools.unknown_tool", tool=name)
            metrics.incr("tools.unknown")
            return ToolResult(
                ok=False,
                error_code="unknown_tool",
                payload={
                    "message": f"'{name}' is not a tool this app provides.",
                    "available_tools": list(self._specs),
                },
            )

        # ---- validate ----
        try:
            args = spec.args_model.model_validate(raw_args or {})
        except ValidationError as exc:
            problems = [
                {"field": ".".join(str(p) for p in e["loc"]) or "(root)", "problem": e["msg"]}
                for e in exc.errors()[:5]
            ]
            log.info("tools.invalid_arguments", tool=name, problems=problems)
            metrics.incr("tools.invalid_args", tool=name)
            await self._audit(ctx, name, raw_args or {}, False, "invalid_arguments", 0)
            return ToolResult(
                ok=False,
                error_code="invalid_arguments",
                payload={
                    "message": "Those arguments were not valid.",
                    "problems": problems,
                    "instruction_to_model": (
                        "Fix the arguments and call the tool again, or ask the user for "
                        "the missing detail. Do not invent the answer."
                    ),
                },
            )

        validated = args.model_dump(exclude_none=True, mode="json")

        # ---- execute ----
        started = time.perf_counter()
        try:
            payload = await spec.handler(ctx, args)
        except FishMateError as exc:
            duration = int((time.perf_counter() - started) * 1000)
            log.warning("tools.failed", tool=name, code=exc.code, duration_ms=duration)
            metrics.incr("tools.failed", tool=name, code=exc.code)
            await self._audit(ctx, name, validated, False, exc.code, duration)
            return ToolResult(
                ok=False,
                error_code=exc.code,
                payload={
                    "message": exc.message,
                    "instruction_to_model": (
                        "Tell the user you could not retrieve that right now, in one "
                        "sentence, then keep helping with what you already have."
                    ),
                },
            )
        except Exception as exc:  # unexpected: log loudly, answer safely
            duration = int((time.perf_counter() - started) * 1000)
            log.exception("tools.crashed", tool=name, error_type=type(exc).__name__)
            metrics.incr("tools.crashed", tool=name)
            await self._audit(ctx, name, validated, False, "internal_error", duration)
            return ToolResult(
                ok=False,
                error_code="internal_error",
                payload={
                    "message": "I couldn't complete that action.",
                    "instruction_to_model": (
                        "Apologise in one sentence and continue the conversation."
                    ),
                },
            )

        duration = int((time.perf_counter() - started) * 1000)
        metrics.incr("tools.ok", tool=name)
        metrics.observe("tools.latency", duration / 1000, tool=name)
        log.info(
            "tools.executed",
            tool=name,
            duration_ms=duration,
            mutates=spec.mutates,
            coords=round_coords(ctx.latitude, ctx.longitude),
        )
        await self._audit(ctx, name, validated, True, None, duration)
        return ToolResult(ok=True, payload=payload)

    async def _audit(
        self,
        ctx: ToolContext,
        name: str,
        args: dict[str, Any],
        ok: bool,
        error_code: str | None,
        duration_ms: int,
    ) -> None:
        """Write the audit row. Never let auditing break a conversation."""
        try:
            ctx.db.add(
                ToolExecution(
                    user_id=ctx.user_id,
                    tool_name=name,
                    arguments=_sanitise_audit_args(args),
                    succeeded=ok,
                    error_code=error_code,
                    duration_ms=duration_ms,
                    live_session_ref=ctx.live_session_ref,
                )
            )
            await ctx.db.flush()
        except Exception:
            log.warning("tools.audit_write_failed", tool=name)


def _sanitise_audit_args(args: dict[str, Any]) -> dict[str, Any]:
    """Coarsen coordinates before they are persisted to the audit trail.

    The audit answers "which tools ran", not "exactly where the user fishes"
    (docs/PRIVACY.md).
    """
    out = dict(args)
    for key in ("latitude", "longitude"):
        if isinstance(out.get(key), (int, float)):
            out[key] = round(float(out[key]), 2)
    return out


_registry: FishingToolRegistry | None = None


def get_tool_registry() -> FishingToolRegistry:
    global _registry
    if _registry is None:
        _registry = FishingToolRegistry()
    return _registry
