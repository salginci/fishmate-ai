"""Shared FastAPI dependencies."""

from __future__ import annotations

from typing import Annotated

from fastapi import Depends, Header
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import Settings, get_settings
from app.core.errors import AuthError
from app.core.observability import user_id_ctx
from app.core.security import decode_access_token
from app.db.models import User
from app.db.session import get_db
from app.repositories import users as user_repo

DbSession = Annotated[AsyncSession, Depends(get_db)]
AppSettings = Annotated[Settings, Depends(get_settings)]


async def get_current_user(
    db: DbSession,
    authorization: Annotated[str | None, Header()] = None,
) -> User:
    """Resolve the bearer token to a user.

    Deliberately not using OAuth2PasswordBearer so the 401 body matches our
    `ErrorResponse` shape, which the Android client maps to localized strings.
    """
    if not authorization or not authorization.lower().startswith("bearer "):
        raise AuthError("Please sign in to continue.")
    token = authorization[7:].strip()
    if not token:
        raise AuthError("Please sign in to continue.")

    payload = decode_access_token(token)
    user = await user_repo.get_by_id(db, payload["sub"])
    if user is None or not user.is_active:
        raise AuthError("Your account is no longer active.")

    user_id_ctx.set(user.id)
    return user


CurrentUser = Annotated[User, Depends(get_current_user)]


def user_language(user: User, override: str | None = None) -> str:
    """Effective language: explicit override, else the saved preference."""
    if override:
        return override
    if user.preferences and user.preferences.language:
        return user.preferences.language
    return "en"
