"""Conditions, recommendations, home summary and health/metrics."""

from __future__ import annotations

from fastapi import APIRouter, Query

from app.config import get_settings
from app.core.errors import FishMateError, ValidationError
from app.core.observability import get_logger, metrics
from app.deps import AppSettings, CurrentUser, DbSession, user_language
from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.repositories import tackle as tackle_repo
from app.schemas.api import (
    CatchResponse,
    ConditionsResponse,
    HomeSummaryResponse,
    RecommendationRequest,
    SessionResponse,
)
from app.services.recommendation import get_recommendation_service
from app.services.weather import ConditionsSnapshot, beaufort, compass, get_weather_provider

log = get_logger("conditions")
router = APIRouter(tags=["conditions"])


def _to_response(snapshot: ConditionsSnapshot, language: str) -> ConditionsResponse:
    return ConditionsResponse(
        summary=snapshot.describe(language),
        observed_at=snapshot.observed_at,
        provider=snapshot.provider,
        air_temp_c=snapshot.air_temp_c,
        water_temp_c=snapshot.water_temp_c,
        wind_speed_kmh=snapshot.wind_speed_kmh,
        wind_gust_kmh=snapshot.wind_gust_kmh,
        wind_direction=compass(snapshot.wind_direction_deg, language),
        wind_beaufort=beaufort(snapshot.wind_speed_kmh),
        wave_height_m=snapshot.wave_height_m,
        swell_period_s=snapshot.swell_period_s,
        cloud_cover_pct=snapshot.cloud_cover_pct,
        precipitation_mm=snapshot.precipitation_mm,
        sunrise=snapshot.sunrise,
        sunset=snapshot.sunset,
        sea_data_available=snapshot.sea_data_available,
    )


@router.get("/conditions", response_model=ConditionsResponse)
async def current_conditions(
    user: CurrentUser,
    latitude: float = Query(ge=-90, le=90),
    longitude: float = Query(ge=-180, le=180),
) -> ConditionsResponse:
    snapshot = await get_weather_provider().current(latitude, longitude)
    return _to_response(snapshot, user_language(user))


@router.post("/recommendation")
async def recommend(body: RecommendationRequest, user: CurrentUser, db: DbSession) -> dict:
    """Same engine the `recommend_setup` tool uses, for the on-screen card."""
    language = user_language(user)
    snapshot = None
    lat, lon = body.latitude, body.longitude
    if lat is None or lon is None:
        active = await session_repo.get_active(db, user.id)
        if active is not None:
            lat, lon = active.latitude, active.longitude
    if lat is not None and lon is not None:
        try:
            snapshot = await get_weather_provider().current(lat, lon)
        except FishMateError:
            log.warning("conditions.recommend_without_weather")

    recommendation = await get_recommendation_service().recommend(
        db,
        user.id,
        conditions=snapshot,
        target_species=body.target_species,
        fishing_style=body.fishing_style,
        notes=body.notes,
        language=language,
    )
    return recommendation.to_tool_payload(language)


@router.get("/home", response_model=HomeSummaryResponse)
async def home_summary(
    user: CurrentUser,
    db: DbSession,
    latitude: float | None = Query(default=None, ge=-90, le=90),
    longitude: float | None = Query(default=None, ge=-180, le=180),
) -> HomeSummaryResponse:
    """One round trip for the whole Home screen.

    Conditions are best-effort: the screen must render on a dead connection,
    so a weather failure becomes `conditions_error`, not a 503.
    """
    language = user_language(user)
    active = await session_repo.get_active(db, user.id)
    recent_sessions = await session_repo.list_recent(db, user.id, limit=2)
    last_completed = next((s for s in recent_sessions if active is None or s.id != active.id), None)
    recent_catches = await catch_repo.list_recent(db, user.id, limit=5)

    conditions = None
    conditions_error = None
    lat, lon = latitude, longitude
    if (lat is None or lon is None) and active is not None:
        lat, lon = active.latitude, active.longitude
    if lat is not None and lon is not None:
        try:
            conditions = _to_response(await get_weather_provider().current(lat, lon), language)
        except FishMateError as exc:
            conditions_error = exc.message

    return HomeSummaryResponse(
        display_name=user.display_name,
        active_session=SessionResponse.model_validate(active) if active else None,
        last_session=(SessionResponse.model_validate(last_completed) if last_completed else None),
        recent_catches=[CatchResponse.model_validate(c) for c in recent_catches],
        tackle_count=await tackle_repo.count_for_user(db, user.id),
        total_catches=await catch_repo.count_for_user(db, user.id),
        conditions=conditions,
        conditions_error=conditions_error,
    )


# ---- Health / observability ----------------------------------------------
health_router = APIRouter(tags=["health"])


@health_router.get("/health")
async def health() -> dict:
    """Liveness. Must stay dependency-free so it answers during an outage."""
    settings = get_settings()
    return {
        "status": "ok",
        "environment": settings.environment,
        "live_api_configured": settings.live_api_configured,
        "weather_provider": settings.weather_provider,
    }


@health_router.get("/health/ready")
async def ready(db: DbSession) -> dict:
    """Readiness — verifies the database is actually reachable."""
    from sqlalchemy import text

    await db.execute(text("SELECT 1"))
    return {"status": "ready"}


@health_router.get("/health/metrics")
async def metrics_snapshot(settings: AppSettings) -> dict:
    if settings.is_production:
        # Counters are not secret, but they are operational detail; in
        # production they belong to the platform's scraper, not the internet.
        raise ValidationError("Metrics are not exposed in this environment.")
    return metrics.snapshot()
