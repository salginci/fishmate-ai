"""Gemini Live credential issuing.

This is the only endpoint that touches the real `GEMINI_API_KEY`, and it never
returns it. See app/services/live_tokens.py for why the token is constrained.
"""

from __future__ import annotations

import asyncio

from fastapi import APIRouter, HTTPException, status

from app.core.location import apply_location_precision
from app.core.observability import get_logger, metrics
from app.deps import AppSettings, CurrentUser, DbSession, user_language
from app.schemas.api import (
    LiveSessionRequest,
    LiveSessionResponse,
    TranslateRequest,
    TranslateResponse,
)
from app.services.geocoding import get_geocoding_provider
from app.services.live_tokens import get_live_token_service
from app.services.memory import get_memory_service
from app.services.translation import TranslationError, get_translation_service
from app.services.weather import get_weather_provider

log = get_logger("live")
router = APIRouter(tags=["live"])


@router.post("/live/session", response_model=LiveSessionResponse)
async def create_live_session(
    body: LiveSessionRequest,
    user: CurrentUser,
    db: DbSession,
    settings: AppSettings,
) -> LiveSessionResponse:
    """Mint a short-lived, config-locked Gemini Live credential for this device.

    The client calls this each time it opens Fishing Mode, and again when a
    token nears expiry. Tokens are cheap; long-lived credentials on a phone are
    not.
    """
    language = user_language(user, body.language)
    voice = body.voice_name or (user.preferences.voice_name if user.preferences else None)

    credentials = await get_live_token_service(settings).mint(language=language, voice_name=voice)

    # A small brief so a fresh conversation already knows who it is talking to,
    # without replaying any transcript. Failure here must not block fishing.
    brief = None
    try:
        brief = await get_memory_service().user_profile_brief(db, user.id, language)
        brief["display_name"] = user.display_name
    except Exception:
        log.warning("live.brief_unavailable", user_id=user.id)

    # Where the angler is, by name. This is what lets the assistant open with
    # "so you're at Bostanlı" instead of "where are you fishing?" — which is a
    # question the phone could have answered for them.
    latitude, longitude = apply_location_precision(
        user.preferences.location_precision if user.preferences else None,
        body.latitude,
        body.longitude,
    )
    if brief is not None and latitude is not None and longitude is not None:
        brief["coordinates"] = {"latitude": latitude, "longitude": longitude}
        # Concurrently, because both only shape the greeting and the angler is
        # waiting to talk. The conditions call also warms the TTL cache that
        # the model's own get_current_conditions will hit seconds later, so it
        # mostly moves latency rather than adding it.
        place_result, conditions_result = await asyncio.gather(
            get_geocoding_provider().reverse(latitude, longitude),
            get_weather_provider().current(latitude, longitude),
            return_exceptions=True,
        )

        if isinstance(place_result, BaseException):
            # A nameless spot still works; the coordinates alone are enough for
            # every tool. Only the greeting is poorer.
            log.warning("live.place_unavailable", user_id=user.id)
        elif place_result is not None:
            brief["place"] = {
                "name": place_result.name,
                "region": place_result.region,
                "full_name": place_result.display_name,
            }

        if isinstance(conditions_result, BaseException):
            log.warning("live.water_unavailable", user_id=user.id)
        else:
            # Whether the marine grid reports a sea state *at this point*.
            #
            # Deliberately a flag and not a distance. Open-Meteo's marine grid
            # is about 9 km across and snaps to the nearest cell, so the
            # distance it implies is meaningless at close range -- probed from
            # the Bostanlı waterfront it still answered 10 km inland on one
            # bearing and 25 km on another. What it does say reliably is the
            # negative: no sea state means genuinely inland, and that is the
            # case the greeting has to handle differently.
            brief["sea_state_at_position"] = conditions_result.sea_data_available

    metrics.incr("live.session_requested", video="yes" if body.expects_video else "no")
    log.info(
        "live.session_issued",
        user_id=user.id,
        language=language,
        expects_video=body.expects_video,
        # Why it matters: audio-only sessions last ~15 min, audio+video ~2 min
        # before the server ends them; compression + resumption carry us past
        # that, but the ceiling explains reconnect frequency in the logs.
        duration_ceiling=("2min_av" if body.expects_video else "15min_audio"),
    )
    return LiveSessionResponse(
        token=credentials.token,
        ws_url=credentials.ws_url,
        model=credentials.model,
        expires_at=credentials.expires_at,
        new_session_expires_at=credentials.new_session_expires_at,
        setup=credentials.setup,
        input_sample_rate_hz=credentials.input_sample_rate_hz,
        output_sample_rate_hz=credentials.output_sample_rate_hz,
        input_audio_mime=credentials.input_audio_mime,
        user_brief=brief,
    )


@router.post("/live/translate", response_model=TranslateResponse)
async def translate_transcript(
    body: TranslateRequest,
    user: CurrentUser,
) -> TranslateResponse:
    """Translate one transcript line for the on-screen subtitle.

    Separate from the Live session on purpose: the session replies in audio,
    and asking it for a parallel text modality would slow the spoken answer
    down. The client calls this only for finalised lines, so the request rate
    is roughly one per conversational turn.
    """
    try:
        translated = await get_translation_service().translate(
            body.text,
            target=body.target_language,
            source=body.source_language,
        )
    except TranslationError as exc:
        # A missing subtitle must never read as a broken conversation, so the
        # client treats this as "no translation yet" rather than an error.
        raise HTTPException(status_code=status.HTTP_502_BAD_GATEWAY, detail=str(exc)) from exc

    log.debug("live.translated", user_id=str(user.id), target=body.target_language)
    metrics.incr("live.translate")
    return TranslateResponse(text=translated, target_language=body.target_language)
