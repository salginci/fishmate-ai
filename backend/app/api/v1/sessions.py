"""Fishing sessions, observations and spots."""

from __future__ import annotations

from fastapi import APIRouter, status

from app.core.errors import NotFoundError
from app.core.location import apply_location_precision
from app.core.observability import get_logger
from app.db.models import LocationPrecision, SessionStatus
from app.deps import CurrentUser, DbSession, user_language
from app.repositories import sessions as session_repo
from app.schemas.api import (
    ObservationCreate,
    ObservationResponse,
    SessionDetailResponse,
    SessionFinishRequest,
    SessionResponse,
    SessionStartRequest,
    SessionUpdateRequest,
    SpotCreate,
    SpotResponse,
)
from app.services.memory import get_memory_service
from app.services.weather import get_weather_provider

log = get_logger("sessions")
router = APIRouter(tags=["sessions"])


def _precision_for(user) -> str:
    return (
        user.preferences.location_precision
        if user.preferences
        else LocationPrecision.APPROXIMATE.value
    )


@router.post("/sessions", response_model=SessionDetailResponse, status_code=status.HTTP_201_CREATED)
async def start_session(
    body: SessionStartRequest, user: CurrentUser, db: DbSession
) -> SessionDetailResponse:
    precision = _precision_for(user)
    lat, lon = apply_location_precision(precision, body.latitude, body.longitude)

    session = await session_repo.start(
        db,
        user.id,
        target_species=body.target_species,
        location_label=body.location_label,
        latitude=lat,
        longitude=lon,
        location_precision=precision if lat is not None else LocationPrecision.NONE.value,
        fishing_style=body.fishing_style,
        tackle_used=body.tackle_used,
    )

    if lat is not None and lon is not None:
        try:
            snapshot = await get_weather_provider().current(lat, lon)
            await session_repo.add_weather_snapshot(
                db,
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
                    "summary": snapshot.describe(user_language(user)),
                },
            )
        except Exception as exc:
            # A session must start even with no network — the angler is already
            # on the shore. The snapshot is simply absent from the report.
            log.warning(
                "sessions.weather_snapshot_unavailable",
                session_id=session.id,
                error_type=type(exc).__name__,
            )

    if body.location_label:
        await session_repo.upsert_spot(
            db,
            user.id,
            name=body.location_label,
            latitude=lat,
            longitude=lon,
            location_precision=precision,
        )
    await db.commit()
    full = await session_repo.get(db, user.id, session.id)
    return SessionDetailResponse.model_validate(full)


@router.get("/sessions", response_model=list[SessionResponse])
async def list_sessions(
    user: CurrentUser,
    db: DbSession,
    limit: int = 20,
    offset: int = 0,
    status_filter: str | None = None,
) -> list[SessionResponse]:
    sessions = await session_repo.list_recent(
        db, user.id, limit=min(limit, 100), offset=offset, status=status_filter
    )
    return [SessionResponse.model_validate(s) for s in sessions]


@router.get("/sessions/active", response_model=SessionDetailResponse)
async def active_session(user: CurrentUser, db: DbSession) -> SessionDetailResponse:
    session = await session_repo.get_active(db, user.id)
    if session is None:
        raise NotFoundError("You have no fishing session running.")
    full = await session_repo.get(db, user.id, session.id)
    return SessionDetailResponse.model_validate(full)


@router.get("/sessions/{session_id}", response_model=SessionDetailResponse)
async def get_session(session_id: str, user: CurrentUser, db: DbSession) -> SessionDetailResponse:
    return SessionDetailResponse.model_validate(await session_repo.get(db, user.id, session_id))


@router.patch("/sessions/{session_id}", response_model=SessionResponse)
async def update_session(
    session_id: str, body: SessionUpdateRequest, user: CurrentUser, db: DbSession
) -> SessionResponse:
    session = await session_repo.update(
        db, user.id, session_id, **body.model_dump(exclude_none=True)
    )
    await db.commit()
    return SessionResponse.model_validate(session)


@router.post("/sessions/{session_id}/finish", response_model=SessionDetailResponse)
async def finish_session(
    session_id: str, body: SessionFinishRequest, user: CurrentUser, db: DbSession
) -> SessionDetailResponse:
    session = await session_repo.get(db, user.id, session_id)
    report = await get_memory_service().build_session_report(db, session, user_language(user))
    await session_repo.finish(
        db,
        user.id,
        session_id,
        notes=body.notes,
        summary=report,
        status=SessionStatus.COMPLETED.value,
    )
    await db.commit()
    return SessionDetailResponse.model_validate(await session_repo.get(db, user.id, session_id))


@router.post(
    "/sessions/{session_id}/observations",
    response_model=ObservationResponse,
    status_code=status.HTTP_201_CREATED,
)
async def add_observation(
    session_id: str, body: ObservationCreate, user: CurrentUser, db: DbSession
) -> ObservationResponse:
    await session_repo.get(db, user.id, session_id)  # ownership check
    observation = await session_repo.add_observation(
        db, session_id, kind=body.kind, text=body.text, from_ai=body.kind == "ai_observation"
    )
    await db.commit()
    return ObservationResponse.model_validate(observation)


@router.delete(
    "/sessions/{session_id}/observations/{observation_id}",
    status_code=status.HTTP_204_NO_CONTENT,
)
async def delete_observation(
    session_id: str, observation_id: str, user: CurrentUser, db: DbSession
) -> None:
    await session_repo.get(db, user.id, session_id)
    await session_repo.delete_observation(db, session_id, observation_id)
    await db.commit()


# ---- Spots ---------------------------------------------------------------
@router.get("/spots", response_model=list[SpotResponse])
async def list_spots(user: CurrentUser, db: DbSession) -> list[SpotResponse]:
    return [SpotResponse.model_validate(s) for s in await session_repo.list_spots(db, user.id)]


@router.post("/spots", response_model=SpotResponse, status_code=status.HTTP_201_CREATED)
async def create_spot(body: SpotCreate, user: CurrentUser, db: DbSession) -> SpotResponse:
    spot = await session_repo.upsert_spot(
        db,
        user.id,
        name=body.name,
        latitude=body.latitude,
        longitude=body.longitude,
        location_precision=_precision_for(user),
        water_type=body.water_type,
        structure_notes=body.structure_notes,
    )
    await db.commit()
    return SpotResponse.model_validate(spot)


@router.delete("/spots/{spot_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_spot(spot_id: str, user: CurrentUser, db: DbSession) -> None:
    await session_repo.delete_spot(db, user.id, spot_id)
    await db.commit()
