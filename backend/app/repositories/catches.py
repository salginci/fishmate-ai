"""Catch log persistence."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.errors import NotFoundError
from app.db.models import Catch, CatchPhoto, FishingSession


async def get(db: AsyncSession, user_id: str, catch_id: str) -> Catch:
    result = await db.execute(
        select(Catch)
        .options(selectinload(Catch.photos))
        .where(Catch.id == catch_id, Catch.user_id == user_id)
        .execution_options(populate_existing=True)
    )
    catch = result.scalar_one_or_none()
    if catch is None:
        raise NotFoundError("That catch could not be found.")
    return catch


async def list_recent(
    db: AsyncSession,
    user_id: str,
    limit: int = 10,
    offset: int = 0,
    species_key: str | None = None,
    days_back: int | None = None,
    session_id: str | None = None,
) -> list[Catch]:
    stmt = select(Catch).options(selectinload(Catch.photos)).where(Catch.user_id == user_id)
    if species_key:
        stmt = stmt.where(Catch.species_key == species_key)
    if session_id:
        stmt = stmt.where(Catch.session_id == session_id)
    if days_back is not None and days_back > 0:
        cutoff = datetime.now(UTC) - timedelta(days=days_back)
        stmt = stmt.where(Catch.caught_at >= cutoff)
    stmt = stmt.order_by(Catch.caught_at.desc()).limit(limit).offset(offset)
    return list((await db.execute(stmt)).scalars())


async def create(db: AsyncSession, user_id: str, **fields) -> Catch:
    catch = Catch(user_id=user_id, **fields)
    db.add(catch)
    await db.flush()
    if catch.session_id:
        await _recount_session(db, catch.session_id)
    await db.refresh(catch, ["photos"])
    return catch


async def update(db: AsyncSession, user_id: str, catch_id: str, **fields) -> Catch:
    catch = await get(db, user_id, catch_id)
    species_changed = (
        "species_key" in fields
        and fields["species_key"] is not None
        and fields["species_key"] != catch.species_key
    )
    for key, value in fields.items():
        if value is not None and hasattr(catch, key):
            setattr(catch, key, value)
    if species_changed:
        # The user overrode the AI's identification: mark it confirmed by them
        # and drop the model's confidence, which no longer describes this row.
        catch.species_confirmed_by_user = True
        catch.ai_confidence = None
    await db.flush()
    return catch


async def confirm_species(db: AsyncSession, user_id: str, catch_id: str) -> Catch:
    catch = await get(db, user_id, catch_id)
    catch.species_confirmed_by_user = True
    await db.flush()
    return catch


async def delete(db: AsyncSession, user_id: str, catch_id: str) -> None:
    catch = await get(db, user_id, catch_id)
    session_id = catch.session_id
    await db.delete(catch)
    await db.flush()
    if session_id:
        await _recount_session(db, session_id)


async def add_photo(db: AsyncSession, catch_id: str, **fields) -> CatchPhoto:
    photo = CatchPhoto(catch_id=catch_id, **fields)
    db.add(photo)
    await db.flush()
    return photo


async def _recount_session(db: AsyncSession, session_id: str) -> None:
    """Keep the denormalised counter honest after any catch mutation."""
    total = await db.execute(
        select(func.count()).select_from(Catch).where(Catch.session_id == session_id)
    )
    session = await db.get(FishingSession, session_id)
    if session is not None:
        session.catch_count = int(total.scalar() or 0)
        await db.flush()


async def species_breakdown(
    db: AsyncSession, user_id: str, days_back: int | None = None
) -> list[tuple[str | None, int]]:
    """(species_key, count) newest-first-agnostic, for history screens."""
    stmt = (
        select(Catch.species_key, func.count().label("n"))
        .where(Catch.user_id == user_id)
        .group_by(Catch.species_key)
        .order_by(func.count().desc())
    )
    if days_back is not None and days_back > 0:
        cutoff = datetime.now(UTC) - timedelta(days=days_back)
        stmt = stmt.where(Catch.caught_at >= cutoff)
    return [(row[0], int(row[1])) for row in (await db.execute(stmt)).all()]


async def count_for_user(db: AsyncSession, user_id: str) -> int:
    result = await db.execute(
        select(func.count()).select_from(Catch).where(Catch.user_id == user_id)
    )
    return int(result.scalar() or 0)
