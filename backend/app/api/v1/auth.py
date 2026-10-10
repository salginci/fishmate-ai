"""Registration, login, profile and account deletion."""

from __future__ import annotations

from fastapi import APIRouter, status

from app.core.errors import AuthError
from app.core.observability import get_logger
from app.core.security import create_access_token
from app.deps import CurrentUser, DbSession
from app.repositories import users as user_repo
from app.schemas.api import (
    LoginRequest,
    PreferencesResponse,
    PreferencesUpdate,
    RegisterRequest,
    TokenResponse,
    UserResponse,
)

log = get_logger("auth")
router = APIRouter(tags=["auth"])


@router.post("/auth/register", response_model=TokenResponse, status_code=status.HTTP_201_CREATED)
async def register(body: RegisterRequest, db: DbSession) -> TokenResponse:
    user = await user_repo.create(
        db,
        email=body.email,
        password=body.password,
        display_name=body.display_name,
        language=body.language,
    )
    # Intentionally no tackle is seeded here: recommending gear the angler
    # does not own is worse than recommending nothing. Onboarding offers
    # starter templates (GET /tackle/templates) that the user opts into.
    await db.commit()
    log.info("auth.registered", user_id=user.id)

    token, expires_in = create_access_token(user.id)
    return TokenResponse(
        access_token=token,
        expires_in=expires_in,
        user=UserResponse.model_validate(user),
    )


@router.post("/auth/login", response_model=TokenResponse)
async def login(body: LoginRequest, db: DbSession) -> TokenResponse:
    user = await user_repo.authenticate(db, body.email, body.password)
    if user is None:
        # Same message for unknown email and wrong password — do not confirm
        # which accounts exist.
        log.info("auth.login_failed")
        raise AuthError("Email or password is incorrect.")
    await db.commit()
    token, expires_in = create_access_token(user.id)
    log.info("auth.login_ok", user_id=user.id)
    return TokenResponse(
        access_token=token,
        expires_in=expires_in,
        user=UserResponse.model_validate(user),
    )


@router.get("/me", response_model=UserResponse)
async def me(user: CurrentUser) -> UserResponse:
    return UserResponse.model_validate(user)


@router.patch("/me/preferences", response_model=PreferencesResponse)
async def update_preferences(
    body: PreferencesUpdate, user: CurrentUser, db: DbSession
) -> PreferencesResponse:
    prefs = await user_repo.update_preferences(db, user, **body.model_dump(exclude_none=True))
    await db.commit()
    return PreferencesResponse.model_validate(prefs)


@router.delete("/me", status_code=status.HTTP_204_NO_CONTENT)
async def delete_account(user: CurrentUser, db: DbSession) -> None:
    """Delete the account and everything owned by it.

    Required by docs/PRIVACY.md: fishing spots and catch history are sensitive
    and the user must be able to remove them permanently.
    """
    user_id = user.id
    await user_repo.delete_user(db, user_id)
    await db.commit()
    log.info("auth.account_deleted", user_id=user_id)
