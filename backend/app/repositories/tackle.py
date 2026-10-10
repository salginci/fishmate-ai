"""Tackle inventory persistence."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.core.errors import NotFoundError
from app.db.models import TackleItem


async def list_for_user(
    db: AsyncSession,
    user_id: str,
    category: str | None = None,
    fishing_style: str | None = None,
    include_inactive: bool = False,
) -> list[TackleItem]:
    stmt = select(TackleItem).where(TackleItem.user_id == user_id)
    if not include_inactive:
        stmt = stmt.where(TackleItem.is_active.is_(True))
    if category:
        stmt = stmt.where(TackleItem.category == category)
    stmt = stmt.order_by(TackleItem.category, TackleItem.is_favourite.desc(), TackleItem.name)
    items = list((await db.execute(stmt)).scalars())
    if fishing_style:
        # JSON column — filter in Python so the query stays portable between
        # Postgres and the SQLite used by tests.
        items = [
            i for i in items if not i.fishing_styles or fishing_style in (i.fishing_styles or [])
        ]
    return items


async def get(db: AsyncSession, user_id: str, item_id: str) -> TackleItem:
    result = await db.execute(
        select(TackleItem).where(TackleItem.id == item_id, TackleItem.user_id == user_id)
    )
    item = result.scalar_one_or_none()
    if item is None:
        raise NotFoundError("That tackle item could not be found.")
    return item


async def create(db: AsyncSession, user_id: str, **fields) -> TackleItem:
    item = TackleItem(user_id=user_id, **fields)
    db.add(item)
    await db.flush()
    return item


async def update(db: AsyncSession, user_id: str, item_id: str, **fields) -> TackleItem:
    item = await get(db, user_id, item_id)
    for key, value in fields.items():
        if value is not None and hasattr(item, key):
            setattr(item, key, value)
    # A user edit is an implicit confirmation: it is no longer an unreviewed
    # AI guess (prompt: "Do not make AI-generated information immutable").
    if fields and item.ai_identified:
        item.ai_identified = False
    await db.flush()
    return item


async def delete(db: AsyncSession, user_id: str, item_id: str) -> None:
    item = await get(db, user_id, item_id)
    await db.delete(item)
    await db.flush()


async def count_for_user(db: AsyncSession, user_id: str) -> int:
    from sqlalchemy import func

    result = await db.execute(
        select(func.count())
        .select_from(TackleItem)
        .where(TackleItem.user_id == user_id, TackleItem.is_active.is_(True))
    )
    return int(result.scalar() or 0)
