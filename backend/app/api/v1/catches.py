"""Catch log CRUD.

The user must always be able to overrule the model: `PATCH /catches/{id}` with
a new species clears the AI confidence and marks the row user-confirmed (see
repositories/catches.py).
"""

from __future__ import annotations

from datetime import UTC, datetime

from fastapi import APIRouter, status

from app.deps import CurrentUser, DbSession, user_language
from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.schemas.api import CatchCreate, CatchResponse, CatchUpdate
from app.services import species as species_catalogue
from app.services.species import CATALOGUE

router = APIRouter(tags=["catches"])


@router.get("/catches", response_model=list[CatchResponse])
async def list_catches(
    user: CurrentUser,
    db: DbSession,
    limit: int = 50,
    offset: int = 0,
    species: str | None = None,
    days_back: int | None = None,
    session_id: str | None = None,
) -> list[CatchResponse]:
    items = await catch_repo.list_recent(
        db,
        user.id,
        limit=min(limit, 200),
        offset=offset,
        species_key=species_catalogue.normalise(species) if species else None,
        days_back=days_back,
        session_id=session_id,
    )
    return [CatchResponse.model_validate(c) for c in items]


@router.post("/catches", response_model=CatchResponse, status_code=status.HTTP_201_CREATED)
async def create_catch(body: CatchCreate, user: CurrentUser, db: DbSession) -> CatchResponse:
    language = user_language(user)
    species_key = species_catalogue.normalise(body.species)
    label = species_catalogue.label_for(species_key, language, fallback=body.species)

    session_id = body.session_id
    lat = lon = None
    location_label = weather_summary = None
    if session_id is None:
        active = await session_repo.get_active(db, user.id)
        if active is not None:
            session_id = active.id
    if session_id:
        session = await session_repo.get(db, user.id, session_id)  # ownership check
        lat, lon = session.latitude, session.longitude
        location_label = session.location_label
        snapshot = await session_repo.latest_weather(db, session.id)
        if snapshot is not None:
            weather_summary = snapshot.summary

    catch = await catch_repo.create(
        db,
        user.id,
        session_id=session_id,
        species_key=species_key,
        species_label=label,
        ai_confidence=body.ai_confidence,
        ai_reasoning=body.ai_reasoning,
        species_confirmed_by_user=body.ai_confidence is None,
        length_cm=body.length_cm,
        weight_g=body.weight_g,
        released=body.released,
        lure_label=body.lure_label,
        lure_tackle_id=body.lure_tackle_id,
        rod_tackle_id=body.rod_tackle_id,
        technique=body.technique,
        retrieve_style=body.retrieve_style,
        notes=body.notes,
        caught_at=body.caught_at or datetime.now(UTC),
        latitude=lat,
        longitude=lon,
        location_label=location_label,
        weather_summary=weather_summary,
    )
    if body.photo_local_uri:
        await catch_repo.add_photo(db, catch.id, local_uri=body.photo_local_uri, is_primary=True)
    await db.commit()
    return CatchResponse.model_validate(await catch_repo.get(db, user.id, catch.id))


@router.get("/catches/species")
async def list_species(user: CurrentUser, language: str | None = None) -> dict:
    """The species catalogue, for pickers and the catch-edit screen."""
    lang = user_language(user, language)
    return {
        "species": [
            {
                "key": s.key,
                "label": s.tr if lang.startswith("tr") else s.en,
                "name_tr": s.tr,
                "name_en": s.en,
                "scientific_name": s.scientific_name,
                "typical_styles": list(s.typical_styles),
            }
            for s in CATALOGUE
        ]
    }


@router.get("/catches/{catch_id}", response_model=CatchResponse)
async def get_catch(catch_id: str, user: CurrentUser, db: DbSession) -> CatchResponse:
    return CatchResponse.model_validate(await catch_repo.get(db, user.id, catch_id))


@router.patch("/catches/{catch_id}", response_model=CatchResponse)
async def update_catch(
    catch_id: str, body: CatchUpdate, user: CurrentUser, db: DbSession
) -> CatchResponse:
    fields = body.model_dump(exclude_none=True)
    if "species" in fields:
        species_key = species_catalogue.normalise(fields.pop("species"))
        fields["species_key"] = species_key
        fields["species_label"] = species_catalogue.label_for(species_key, user_language(user))
    catch = await catch_repo.update(db, user.id, catch_id, **fields)
    await db.commit()
    return CatchResponse.model_validate(catch)


@router.post("/catches/{catch_id}/confirm-species", response_model=CatchResponse)
async def confirm_species(catch_id: str, user: CurrentUser, db: DbSession) -> CatchResponse:
    """Accept the AI's identification as correct."""
    catch = await catch_repo.confirm_species(db, user.id, catch_id)
    await db.commit()
    return CatchResponse.model_validate(catch)


@router.delete("/catches/{catch_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_catch(catch_id: str, user: CurrentUser, db: DbSession) -> None:
    await catch_repo.delete(db, user.id, catch_id)
    await db.commit()
