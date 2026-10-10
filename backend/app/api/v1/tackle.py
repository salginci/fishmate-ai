"""Tackle inventory CRUD."""

from __future__ import annotations

from fastapi import APIRouter, Query, status

from app.deps import CurrentUser, DbSession, user_language
from app.repositories import tackle as tackle_repo
from app.schemas.api import TackleItemCreate, TackleItemResponse, TackleItemUpdate
from app.services.onboarding import apply_templates, list_templates

router = APIRouter(tags=["tackle"])


@router.get("/tackle", response_model=list[TackleItemResponse])
async def list_tackle(
    user: CurrentUser,
    db: DbSession,
    category: str | None = None,
    fishing_style: str | None = None,
    include_inactive: bool = False,
) -> list[TackleItemResponse]:
    items = await tackle_repo.list_for_user(
        db,
        user.id,
        category=category,
        fishing_style=fishing_style,
        include_inactive=include_inactive,
    )
    return [TackleItemResponse.model_validate(i) for i in items]


@router.post("/tackle", response_model=TackleItemResponse, status_code=status.HTTP_201_CREATED)
async def create_tackle(
    body: TackleItemCreate, user: CurrentUser, db: DbSession
) -> TackleItemResponse:
    item = await tackle_repo.create(db, user.id, **body.model_dump())
    await db.commit()
    return TackleItemResponse.model_validate(item)


@router.get("/tackle/templates")
async def tackle_templates(user: CurrentUser, language: str | None = None) -> dict:
    """Starter templates shown during onboarding. Nothing is added until asked."""
    return {"templates": list_templates(user_language(user, language))}


@router.post("/tackle/templates", response_model=list[TackleItemResponse])
async def add_tackle_templates(
    user: CurrentUser,
    db: DbSession,
    keys: list[str] = Query(default=[], description="Template keys to apply."),
) -> list[TackleItemResponse]:
    created = await apply_templates(db, user.id, keys)
    await db.commit()
    return [TackleItemResponse.model_validate(i) for i in created]


@router.get("/tackle/{item_id}", response_model=TackleItemResponse)
async def get_tackle(item_id: str, user: CurrentUser, db: DbSession) -> TackleItemResponse:
    return TackleItemResponse.model_validate(await tackle_repo.get(db, user.id, item_id))


@router.patch("/tackle/{item_id}", response_model=TackleItemResponse)
async def update_tackle(
    item_id: str, body: TackleItemUpdate, user: CurrentUser, db: DbSession
) -> TackleItemResponse:
    item = await tackle_repo.update(db, user.id, item_id, **body.model_dump(exclude_none=True))
    await db.commit()
    return TackleItemResponse.model_validate(item)


@router.delete("/tackle/{item_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_tackle(item_id: str, user: CurrentUser, db: DbSession) -> None:
    await tackle_repo.delete(db, user.id, item_id)
    await db.commit()
