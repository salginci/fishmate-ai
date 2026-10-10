"""Fishing session persistence."""

from __future__ import annotations

from datetime import UTC, datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.errors import ConflictError, NotFoundError
from app.db.models import (
    Catch,
    FishingSession,
    FishingSpot,
    LocationPrecision,
    SessionObservation,
    SessionStatus,
    WeatherSnapshot,
)


async def get_active(db: AsyncSession, user_id: str) -> FishingSession | None:
    result = await db.execute(
        select(FishingSession)
        .options(
            # `.photos` must be eager as well: serialising a session detail
            # response touches it, and a lazy load inside the async context
            # raises MissingGreenlet instead of returning data.
            selectinload(FishingSession.catches).selectinload(Catch.photos),
            selectinload(FishingSession.observations),
            selectinload(FishingSession.weather_snapshots),
        )
        .where(
            FishingSession.user_id == user_id,
            FishingSession.status == SessionStatus.ACTIVE.value,
        )
        .order_by(FishingSession.started_at.desc())
        # Refresh eagerly-loaded collections rather than returning the
        # identity-mapped instance as-is: a catch logged earlier in this same
        # unit of work must be visible, or the session report under-counts.
        .execution_options(populate_existing=True)
    )
    return result.scalars().first()


async def get(db: AsyncSession, user_id: str, session_id: str) -> FishingSession:
    result = await db.execute(
        select(FishingSession)
        .options(
            selectinload(FishingSession.catches).selectinload(Catch.photos),
            selectinload(FishingSession.observations),
            selectinload(FishingSession.weather_snapshots),
            selectinload(FishingSession.recommendations),
            selectinload(FishingSession.spot),
        )
        .where(FishingSession.id == session_id, FishingSession.user_id == user_id)
        .execution_options(populate_existing=True)
    )
    session = result.scalar_one_or_none()
    if session is None:
        raise NotFoundError("That fishing session could not be found.")
    return session


async def list_recent(
    db: AsyncSession,
    user_id: str,
    limit: int = 10,
    offset: int = 0,
    status: str | None = None,
) -> list[FishingSession]:
    stmt = (
        select(FishingSession)
        .options(selectinload(FishingSession.catches).selectinload(Catch.photos))
        .where(FishingSession.user_id == user_id)
    )
    if status:
        stmt = stmt.where(FishingSession.status == status)
    stmt = stmt.order_by(FishingSession.started_at.desc()).limit(limit).offset(offset)
    return list((await db.execute(stmt)).scalars())


async def start(
    db: AsyncSession,
    user_id: str,
    *,
    target_species: list[str] | None = None,
    location_label: str | None = None,
    latitude: float | None = None,
    longitude: float | None = None,
    location_precision: str = LocationPrecision.NONE.value,
    fishing_style: str | None = None,
    tackle_used: list[str] | None = None,
    spot_id: str | None = None,
    started_at: datetime | None = None,
) -> FishingSession:
    existing = await get_active(db, user_id)
    if existing is not None:
        raise ConflictError(
            "You already have a fishing session running. Finish it before starting another."
        )
    session = FishingSession(
        user_id=user_id,
        spot_id=spot_id,
        status=SessionStatus.ACTIVE.value,
        started_at=started_at or datetime.now(UTC),
        target_species=target_species or [],
        location_label=location_label,
        latitude=latitude,
        longitude=longitude,
        location_precision=location_precision,
        fishing_style=fishing_style,
        tackle_used=tackle_used or [],
        catch_count=0,
    )
    db.add(session)
    await db.flush()
    await db.refresh(session, ["catches", "observations", "weather_snapshots"])
    return session


async def update(db: AsyncSession, user_id: str, session_id: str, **fields) -> FishingSession:
    session = await get(db, user_id, session_id)
    for key, value in fields.items():
        if value is not None and hasattr(session, key):
            setattr(session, key, value)
    await db.flush()
    return session


async def finish(
    db: AsyncSession,
    user_id: str,
    session_id: str,
    *,
    notes: str | None = None,
    summary: str | None = None,
    status: str = SessionStatus.COMPLETED.value,
    ended_at: datetime | None = None,
) -> FishingSession:
    session = await get(db, user_id, session_id)
    if session.status != SessionStatus.ACTIVE.value:
        raise ConflictError("That session is already finished.")
    session.status = status
    session.ended_at = ended_at or datetime.now(UTC)
    if notes:
        session.notes = f"{session.notes}\n{notes}".strip() if session.notes else notes
    if summary:
        session.summary = summary
    await db.flush()
    return session


async def add_observation(
    db: AsyncSession,
    session_id: str,
    kind: str,
    text: str,
    from_ai: bool = False,
    observed_at: datetime | None = None,
) -> SessionObservation:
    observation = SessionObservation(
        session_id=session_id,
        kind=kind,
        text=text,
        from_ai=from_ai,
        observed_at=observed_at or datetime.now(UTC),
    )
    db.add(observation)
    await db.flush()
    return observation


async def delete_observation(db: AsyncSession, session_id: str, observation_id: str) -> None:
    result = await db.execute(
        select(SessionObservation).where(
            SessionObservation.id == observation_id,
            SessionObservation.session_id == session_id,
        )
    )
    observation = result.scalar_one_or_none()
    if observation is None:
        raise NotFoundError("That observation could not be found.")
    await db.delete(observation)
    await db.flush()


async def add_weather_snapshot(
    db: AsyncSession, session_id: str | None, snapshot_fields: dict
) -> WeatherSnapshot:
    snapshot = WeatherSnapshot(session_id=session_id, **snapshot_fields)
    db.add(snapshot)
    await db.flush()
    return snapshot


async def latest_weather(db: AsyncSession, session_id: str) -> WeatherSnapshot | None:
    result = await db.execute(
        select(WeatherSnapshot)
        .where(WeatherSnapshot.session_id == session_id)
        .order_by(WeatherSnapshot.captured_at.desc())
    )
    return result.scalars().first()


# ---------------------------------------------------------------------------
# Spots
# ---------------------------------------------------------------------------
async def list_spots(db: AsyncSession, user_id: str) -> list[FishingSpot]:
    result = await db.execute(
        select(FishingSpot).where(FishingSpot.user_id == user_id).order_by(FishingSpot.name)
    )
    return list(result.scalars())


async def upsert_spot(
    db: AsyncSession,
    user_id: str,
    name: str,
    latitude: float | None = None,
    longitude: float | None = None,
    location_precision: str = LocationPrecision.MANUAL.value,
    water_type: str | None = None,
    structure_notes: str | None = None,
) -> FishingSpot:
    result = await db.execute(
        select(FishingSpot).where(FishingSpot.user_id == user_id, FishingSpot.name == name)
    )
    spot = result.scalar_one_or_none()
    if spot is None:
        spot = FishingSpot(user_id=user_id, name=name)
        db.add(spot)
    if latitude is not None:
        spot.latitude = latitude
    if longitude is not None:
        spot.longitude = longitude
    if water_type:
        spot.water_type = water_type
    if structure_notes:
        spot.structure_notes = structure_notes
    spot.location_precision = location_precision
    await db.flush()
    return spot


async def delete_spot(db: AsyncSession, user_id: str, spot_id: str) -> None:
    result = await db.execute(
        select(FishingSpot).where(FishingSpot.id == spot_id, FishingSpot.user_id == user_id)
    )
    spot = result.scalar_one_or_none()
    if spot is None:
        raise NotFoundError("That spot could not be found.")
    await db.delete(spot)
    await db.flush()
