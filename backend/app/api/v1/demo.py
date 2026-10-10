"""Demo Mode — deterministic data for the Google presentation.

Design constraint from the brief: *do not fake Gemini Live*. So this module
changes nothing about the Live session, the tool layer, or the database path.
A demo run exercises exactly the production code. What it does control is the
two things a live stage cannot guarantee:

1. **External data** — the weather provider is swapped for the deterministic
   `MockWeatherProvider`, so "the wind is 14 km/h" is the same in rehearsal and
   on stage, and a flaky conference network cannot break the narrative.
2. **History** — "what did I use last time for seabass?" needs a past to
   recall. `POST /demo/seed` writes a believable 6-week history so the fishing
   memory tool has something real to retrieve.

Disabled unless `DEMO_MODE_ENABLED=true`, and refused outright in production.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any

from fastapi import APIRouter

from app.core.errors import ForbiddenError
from app.core.observability import get_logger
from app.db.models import SessionStatus, TackleCategory
from app.deps import AppSettings, CurrentUser, DbSession, user_language
from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.repositories import tackle as tackle_repo
from app.services.weather import MockWeatherProvider, get_weather_provider, set_weather_provider

log = get_logger("demo")
router = APIRouter(tags=["demo"])

#: Bostanlı, İzmir — the reference spot for the demo script.
DEMO_LAT, DEMO_LON = 38.46, 27.09
DEMO_SPOT = "Bostanlı"


def _guard(settings) -> None:
    if settings.is_production or not settings.demo_mode_enabled:
        raise ForbiddenError("Demo mode is disabled in this environment.")


@router.get("/demo/scenarios")
async def scenarios(user: CurrentUser, settings: AppSettings) -> dict:
    """The presentation runsheet, served to the app's Demo screen.

    Keeping it here rather than in the APK means the script can be adjusted
    minutes before a talk without a rebuild.
    """
    _guard(settings)
    tr = user_language(user).startswith("tr")
    return {
        "spot": {"name": DEMO_SPOT, "latitude": DEMO_LAT, "longitude": DEMO_LON},
        "scenarios": [
            {
                "id": 1,
                "title": "Ne kullanayım?" if tr else "Which lure should I use?",
                "say": "Gemini, şu an ne kullanayım?"
                if tr
                else "Gemini, what should I use right now?",
                "capability": "Bidirectional audio + function calling (recommend_setup)",
                "expect": (
                    "A spoken setup recommendation citing measured wind/wave "
                    "and the user's own tackle."
                ),
            },
            {
                "id": 2,
                "title": "Sahteyi göster" if tr else "Show the lure",
                "say": "Bu sahteyle ne yapayım?" if tr else "What should I do with this lure?",
                "capability": "Realtime video input + multimodal reasoning",
                "expect": (
                    "Observation of what is visible, then a hedged inference, "
                    "then a retrieve suggestion."
                ),
            },
            {
                "id": 3,
                "title": "Balığı tanı" if tr else "Identify the fish",
                "say": "Bu ne balığı?" if tr else "What fish is this?",
                "capability": "Vision + calibrated uncertainty",
                "expect": (
                    "A likely species with visual cues and an explicit request "
                    "for a better angle — never certainty."
                ),
            },
            {
                "id": 4,
                "title": "Oturum başlat" if tr else "Start a session",
                "say": "Av seansını başlat." if tr else "Start a fishing session.",
                "capability": "Function calling with state mutation (start_fishing_session)",
                "expect": ("Session created with a weather snapshot; brief spoken confirmation."),
            },
            {
                "id": 5,
                "title": "Avı kaydet" if tr else "Log this catch",
                "say": "Bunu kaydet, 42 santim." if tr else "Log this one, 42 centimetres.",
                "capability": "Multi-tool turn (log_catch) + editable AI assumptions",
                "expect": (
                    "Catch logged, species stated as a likelihood, user told they can correct it."
                ),
            },
            {
                "id": 6,
                "title": "Hafıza" if tr else "Fishing memory",
                "say": "Geçen sefer neyle levrek aldım?"
                if tr
                else "What did I catch seabass with last time?",
                "capability": "Retrieval over persisted history (get_recent_catches)",
                "expect": (
                    "A concrete answer from the database — lure, count and time-of-day pattern."
                ),
            },
            {
                "id": 7,
                "title": "Mevzuat" if tr else "Regulations",
                "say": "Levrek için yasak boy kaç?"
                if tr
                else "What's the minimum size for seabass?",
                "capability": "Grounded refusal — safety behaviour",
                "expect": (
                    "An explicit 'I can't confirm that' plus the official "
                    "authority. Never an invented number."
                ),
            },
            {
                "id": 8,
                "title": "Oturumu bitir" if tr else "Finish the session",
                "say": "Seansı bitir." if tr else "Finish the session.",
                "capability": "Function calling + generated report",
                "expect": ("Spoken headline numbers; full report rendered on screen."),
            },
        ],
        "fallback": (
            "If the Live connection drops, the app shows 'Reconnecting…' and resumes "
            "with its session handle. If Gemini is unreachable entirely, switch to the "
            "Sessions tab — history, tackle and reports are all local-first."
        ),
    }


@router.post("/demo/mock-weather")
async def enable_mock_weather(
    settings: AppSettings, user: CurrentUser, enabled: bool = True
) -> dict:
    """Swap the weather provider for the deterministic one (process-wide)."""
    _guard(settings)
    set_weather_provider(MockWeatherProvider(settings) if enabled else None)
    provider = get_weather_provider()
    log.info("demo.weather_provider_set", provider=provider.name, by=user.id)
    return {"weather_provider": provider.name, "deterministic": enabled}


@router.post("/demo/seed")
async def seed_demo_history(
    user: CurrentUser, db: DbSession, settings: AppSettings, reset: bool = False
) -> dict:
    """Write a believable six-week history for the signed-in user.

    Shaped so the memory tool has a real pattern to find: seabass come to an
    11 g minnow around dusk, bream to a 7 g jighead on the bottom. That is the
    answer scenario 6 is meant to surface, and it is genuinely computed from
    these rows rather than scripted.
    """
    _guard(settings)

    if reset:
        for session in await session_repo.list_recent(db, user.id, limit=100):
            await db.delete(session)
        for catch in await catch_repo.list_recent(db, user.id, limit=500):
            await db.delete(catch)
        await db.flush()

    # --- tackle -----------------------------------------------------------
    existing = {
        i.name.strip().lower()
        for i in await tackle_repo.list_for_user(db, user.id, include_inactive=True)
    }
    wanted: list[dict[str, Any]] = [
        {
            "category": TackleCategory.ROD.value,
            "name": "LRF kamış 1-8 g",
            "weight_min_g": 1,
            "weight_max_g": 8,
            "fishing_styles": ["lrf"],
        },
        {
            "category": TackleCategory.ROD.value,
            "name": "Light spin kamış 3-15 g",
            "weight_min_g": 3,
            "weight_max_g": 15,
            "fishing_styles": ["light_spin"],
            "is_favourite": True,
        },
        {
            "category": TackleCategory.ROD.value,
            "name": "Spin kamış 7-28 g",
            "weight_min_g": 7,
            "weight_max_g": 28,
            "fishing_styles": ["spin"],
        },
        {
            "category": TackleCategory.ROD.value,
            "name": "Surf kamış 100-200 g",
            "weight_min_g": 100,
            "weight_max_g": 200,
            "fishing_styles": ["surf"],
        },
        {
            "category": TackleCategory.MINNOW.value,
            "name": "Minnow 11 cm / 11 g",
            "weight_min_g": 11,
            "weight_max_g": 11,
            "length_cm": 11,
            "fishing_styles": ["spin", "light_spin"],
            "target_species": ["dicentrarchus_labrax"],
            "is_favourite": True,
        },
        {
            "category": TackleCategory.JIGHEAD.value,
            "name": "Jighead 7 g",
            "weight_min_g": 7,
            "weight_max_g": 7,
            "fishing_styles": ["light_spin"],
        },
        {
            "category": TackleCategory.JIGHEAD.value,
            "name": "Jighead 3 g",
            "weight_min_g": 3,
            "weight_max_g": 3,
            "fishing_styles": ["lrf"],
        },
        {
            "category": TackleCategory.SOFT_PLASTIC.value,
            "name": "Soft plastic 7 cm",
            "weight_min_g": 3,
            "weight_max_g": 5,
            "length_cm": 7,
            "fishing_styles": ["light_spin", "lrf"],
            "target_species": ["sparus_aurata"],
        },
        {
            "category": TackleCategory.METAL_JIG.value,
            "name": "Metal jig 20 g",
            "weight_min_g": 20,
            "weight_max_g": 20,
            "fishing_styles": ["spin"],
        },
        {"category": TackleCategory.LINE.value, "name": "PE 0.6", "line_rating": "PE 0.6"},
        {
            "category": TackleCategory.LEADER.value,
            "name": "Fluorocarbon 0.22 mm",
            "line_rating": "0.22 mm FC",
        },
    ]
    tackle_created = 0
    for fields in wanted:
        if fields["name"].strip().lower() not in existing:
            await tackle_repo.create(db, user.id, **fields)
            tackle_created += 1

    await session_repo.upsert_spot(
        db,
        user.id,
        name=DEMO_SPOT,
        latitude=DEMO_LAT,
        longitude=DEMO_LON,
        location_precision="approximate",
        water_type="sea",
        structure_notes="Taş dolgu kıyı, akıntı hattı ve kum geçişleri.",
    )

    # --- history ----------------------------------------------------------
    # (days_ago, start_hour, duration_min, targets, catches)
    plan: list[tuple[int, int, int, list[str], list[tuple[str, float, str, str]]]] = [
        (
            38,
            17,
            150,
            ["levrek"],
            [
                ("levrek", 46.0, "Minnow 11 cm / 11 g", "light spin, akşamüstü at-çek"),
            ],
        ),
        (
            31,
            7,
            120,
            ["çipura"],
            [
                ("çipura", 28.0, "Soft plastic 7 cm", "dipte kısa beklemeli retrieve"),
                ("çipura", 24.5, "Soft plastic 7 cm", "dipte kısa beklemeli retrieve"),
            ],
        ),
        (
            24,
            18,
            165,
            ["levrek"],
            [
                ("levrek", 52.0, "Minnow 11 cm / 11 g", "kıyıya paralel düzenli at-çek"),
                ("levrek", 38.0, "Minnow 11 cm / 11 g", "kıyıya paralel düzenli at-çek"),
            ],
        ),
        (
            17,
            6,
            100,
            ["izmarit", "istavrit"],
            [
                ("istavrit", 18.0, "Jighead 3 g", "LRF, dipte sıçramalı"),
            ],
        ),
        (11, 19, 140, ["levrek"], []),
        # This levrek deliberately came to the metal jig, not the minnow, so
        # the memory tool has to actually count rather than echo one lure.
        (
            5,
            17,
            135,
            ["çipura", "levrek"],
            [
                ("çipura", 31.0, "Jighead 7 g", "dipte bekleme"),
                ("levrek", 41.0, "Metal jig 20 g", "akıntı hattında hızlı at-çek"),
            ],
        ),
    ]

    sessions_created = catches_created = 0
    now = datetime.now(UTC)
    for days_ago, hour, minutes, targets, catch_rows in plan:
        start = (now - timedelta(days=days_ago)).replace(
            hour=hour, minute=0, second=0, microsecond=0
        )
        session = await session_repo.start(
            db,
            user.id,
            target_species=targets,
            location_label=DEMO_SPOT,
            latitude=DEMO_LAT,
            longitude=DEMO_LON,
            location_precision="approximate",
            fishing_style="light_spin",
            started_at=start,
        )
        await session_repo.add_weather_snapshot(
            db,
            session.id,
            {
                "captured_at": start,
                "provider": "mock",
                "latitude": DEMO_LAT,
                "longitude": DEMO_LON,
                "air_temp_c": 19.0,
                "water_temp_c": 21.0,
                "wind_speed_kmh": 12.0 + days_ago % 7,
                "wind_direction_deg": 250.0,
                "wave_height_m": 0.3,
                "summary": "Parçalı bulutlu, 19°C, rüzgâr GB 14 km/h (Bf 3), dalga 0.3 m",
            },
        )
        # Cluster catches late in the session: that is the real pattern the
        # report and the memory tool are meant to detect.
        for index, (species, length, lure, technique) in enumerate(catch_rows):
            await catch_repo.create(
                db,
                user.id,
                session_id=session.id,
                species_key=None,
                species_label=species,
                caught_at=start + timedelta(minutes=minutes - 50 + index * 18),
                length_cm=length,
                lure_label=lure,
                technique=technique,
                released=True,
                species_confirmed_by_user=True,
                location_label=DEMO_SPOT,
                latitude=DEMO_LAT,
                longitude=DEMO_LON,
                weather_summary="Parçalı bulutlu, rüzgâr GB 14 km/h, dalga 0.3 m",
            )
            catches_created += 1
        if catch_rows:
            await session_repo.add_observation(
                db,
                session.id,
                kind="bite",
                text="Son yarım saatte üst üste vuruş alındı.",
                observed_at=start + timedelta(minutes=minutes - 40),
            )
        # Normalise species keys the same way the live path does.
        from app.services import species as species_catalogue

        for catch in await catch_repo.list_recent(db, user.id, limit=500, session_id=session.id):
            if catch.species_key is None:
                catch.species_key = species_catalogue.normalise(catch.species_label)
                catch.species_label = species_catalogue.label_for(
                    catch.species_key, user_language(user), fallback=catch.species_label
                )
        await db.flush()

        ended = start + timedelta(minutes=minutes)
        full = await session_repo.get(db, user.id, session.id)
        from app.services.memory import get_memory_service

        full.ended_at = ended
        report = await get_memory_service().build_session_report(db, full, user_language(user))
        await session_repo.finish(
            db,
            user.id,
            session.id,
            summary=report,
            status=SessionStatus.COMPLETED.value,
            ended_at=ended,
        )
        sessions_created += 1

    await db.commit()
    log.info(
        "demo.seeded",
        user_id=user.id,
        sessions=sessions_created,
        catches=catches_created,
        tackle=tackle_created,
    )
    return {
        "sessions_created": sessions_created,
        "catches_created": catches_created,
        "tackle_created": tackle_created,
        "spot": DEMO_SPOT,
        "note": (
            "Deterministic history written. Scenario 6 ('what did I use last time for "
            "levrek?') now resolves from real rows: 3 of 4 levrek came to the 11 g minnow."
        ),
    }
