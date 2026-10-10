"""User and preference persistence."""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import selectinload

from app.core.errors import ConflictError, NotFoundError
from app.core.security import hash_password, verify_password
from app.db.models import LocationPrecision, User, UserPreferences


async def get_by_email(db: AsyncSession, email: str) -> User | None:
    result = await db.execute(
        select(User)
        .options(selectinload(User.preferences))
        .where(User.email == email.strip().lower())
    )
    return result.scalar_one_or_none()


async def get_by_id(db: AsyncSession, user_id: str) -> User | None:
    result = await db.execute(
        select(User).options(selectinload(User.preferences)).where(User.id == user_id)
    )
    return result.scalar_one_or_none()


async def create(
    db: AsyncSession, email: str, password: str, display_name: str, language: str = "en"
) -> User:
    email = email.strip().lower()
    if await get_by_email(db, email) is not None:
        raise ConflictError("An account with that email already exists.")
    user = User(
        email=email,
        hashed_password=hash_password(password),
        display_name=display_name.strip() or email.split("@")[0],
    )
    db.add(user)
    await db.flush()
    db.add(
        UserPreferences(
            user_id=user.id,
            language=language,
            location_precision=LocationPrecision.APPROXIMATE.value,
            default_target_species=[],
        )
    )
    await db.flush()
    await db.refresh(user, ["preferences"])
    return user


async def authenticate(db: AsyncSession, email: str, password: str) -> User | None:
    user = await get_by_email(db, email)
    if user is None or not user.is_active:
        return None
    if not verify_password(password, user.hashed_password):
        return None
    return user


async def ensure_preferences(db: AsyncSession, user: User) -> UserPreferences:
    if user.preferences is not None:
        return user.preferences
    prefs = UserPreferences(user_id=user.id, default_target_species=[])
    db.add(prefs)
    await db.flush()
    await db.refresh(user, ["preferences"])
    return prefs


async def update_preferences(db: AsyncSession, user: User, **fields) -> UserPreferences:
    prefs = await ensure_preferences(db, user)
    allowed = {
        "language",
        "units",
        "location_precision",
        "default_target_species",
        "default_fishing_style",
        "voice_name",
        "verbosity",
        "camera_fps",
        "share_anonymous_stats",
    }
    for key, value in fields.items():
        if key in allowed and value is not None:
            setattr(prefs, key, value)
    await db.flush()
    return prefs


async def delete_user(db: AsyncSession, user_id: str) -> None:
    """Hard delete. Cascades remove sessions, catches, tackle and spots."""
    user = await get_by_id(db, user_id)
    if user is None:
        raise NotFoundError("Account not found.")
    await db.delete(user)
    await db.flush()
