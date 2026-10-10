"""Password hashing and JWT issuing/verification for the app's own API.

This is distinct from Gemini Live credentials: these tokens authenticate the
Android client *to the FishMate backend*. The backend then mints separate,
short-lived, constrained Gemini ephemeral tokens (see services/live_tokens.py).
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

import bcrypt
from jose import JWTError, jwt

from app.config import get_settings
from app.core.errors import AuthError

#: bcrypt directly rather than passlib: passlib 1.7.x is unmaintained and its
#: bcrypt backend probe breaks against bcrypt >= 4.1.
_BCRYPT_ROUNDS = 12
#: bcrypt's own hard limit. It *truncates* silently past this, which would mean
#: a 72-byte prefix of a password authenticates the account, so we reject.
_MAX_PASSWORD_BYTES = 72

TOKEN_TYPE_ACCESS = "access"  # noqa: S105 - a token *type* label, not a credential


def hash_password(password: str) -> str:
    encoded = password.encode("utf-8")
    if len(encoded) > _MAX_PASSWORD_BYTES:
        raise AuthError("Password must be at most 72 bytes long.")
    return bcrypt.hashpw(encoded, bcrypt.gensalt(rounds=_BCRYPT_ROUNDS)).decode("ascii")


def verify_password(plain: str, hashed: str) -> bool:
    encoded = plain.encode("utf-8")
    if len(encoded) > _MAX_PASSWORD_BYTES:
        return False
    try:
        return bcrypt.checkpw(encoded, hashed.encode("utf-8"))
    except (ValueError, TypeError):
        # Malformed stored hash — treat as a failed login, never a 500.
        return False


def create_access_token(subject: str, extra: dict[str, Any] | None = None) -> tuple[str, int]:
    """Return (jwt, expires_in_seconds)."""
    settings = get_settings()
    ttl = timedelta(minutes=settings.access_token_expire_minutes)
    now = datetime.now(UTC)
    payload: dict[str, Any] = {
        "sub": subject,
        "typ": TOKEN_TYPE_ACCESS,
        "iat": int(now.timestamp()),
        "exp": int((now + ttl).timestamp()),
    }
    if extra:
        payload.update(extra)
    token = jwt.encode(payload, settings.auth_secret, algorithm=settings.auth_algorithm)
    return token, int(ttl.total_seconds())


def decode_access_token(token: str) -> dict[str, Any]:
    settings = get_settings()
    try:
        payload = jwt.decode(token, settings.auth_secret, algorithms=[settings.auth_algorithm])
    except JWTError as exc:
        raise AuthError("Your session has expired. Please sign in again.") from exc
    if payload.get("typ") != TOKEN_TYPE_ACCESS:
        raise AuthError("Unsupported credential type.")
    if not payload.get("sub"):
        raise AuthError("Malformed credential.")
    return payload
