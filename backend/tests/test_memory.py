"""Fishing memory: retrieval and honest aggregation."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.repositories import users as user_repo
from app.services.memory import PATTERN_MIN_SAMPLES, get_memory_service
from app.services.species import normalise


async def _seed_seabass_history(s, user_id, count=4):
    """Three of four seabass on the 11 g minnow, all around dusk."""
    base = datetime.now(UTC) - timedelta(days=30)
    rows = [
        ("Minnow 11 cm / 11 g", 46.0, 0),
        ("Minnow 11 cm / 11 g", 52.0, 7),
        ("Metal jig 20 g", 38.0, 14),
        ("Minnow 11 cm / 11 g", 41.0, 21),
    ][:count]
    for lure, length, day_offset in rows:
        await catch_repo.create(
            s,
            user_id,
            species_key=normalise("levrek"),
            species_label="Levrek",
            caught_at=(base + timedelta(days=day_offset)).replace(hour=18),
            length_cm=length,
            lure_label=lure,
            technique="light spin, akşamüstü at-çek",
            species_confirmed_by_user=True,
        )


class TestCatchDigest:
    async def test_no_history_says_so_explicitly(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        assert digest.total == 0
        payload = digest.to_tool_payload("tr")
        assert "NO recorded catch" in payload["instruction_to_model"]
        assert "Do not invent" in payload["instruction_to_model"]

    async def test_finds_the_lure_that_produced(self, registered, session_factory):
        """This is the 'geçen sefer neyle levrek aldım?' answer."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        assert digest.total == 4
        assert digest.top_lures[0]["lure"] == "Minnow 11 cm / 11 g"
        assert digest.top_lures[0]["catches"] == 3

    async def test_detects_time_of_day_pattern(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        assert digest.has_enough_data_for_pattern
        assert digest.time_of_day_pattern is not None

    async def test_two_records_are_not_called_a_pattern(self, registered, session_factory):
        """Three data points is not a trend, and must not be narrated as one."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id, count=2)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        assert digest.total < PATTERN_MIN_SAMPLES
        assert not digest.has_enough_data_for_pattern
        assert digest.time_of_day_pattern is None
        assert "too few for a pattern" in digest.to_tool_payload("tr")["instruction_to_model"]

    async def test_species_filter_is_name_agnostic(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            for query in ("levrek", "seabass", "Sea Bass"):
                digest = await get_memory_service().catch_digest(s, user.id, species=query)
                assert digest.total == 4, query

    async def test_limit_bounds_rows_but_not_aggregates(self, registered, session_factory):
        """The digest describes real history even when few rows are shown."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek", limit=1)
        assert len(digest.catches) == 1
        assert digest.total == 4

    async def test_statistics_computed(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        assert digest.largest_length_cm == 52.0
        assert digest.average_length_cm == 44.2  # 177/4 = 44.25, banker's rounding

    async def test_ai_identification_is_flagged_as_unconfirmed(self, registered, session_factory):
        """History must not present an unreviewed AI guess as established fact."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await catch_repo.create(
                s,
                user.id,
                species_key=normalise("çipura"),
                species_label="Çipura",
                caught_at=datetime.now(UTC),
                ai_confidence=0.6,
                species_confirmed_by_user=False,
                lure_label="Jighead 7 g",
            )
            digest = await get_memory_service().catch_digest(s, user.id)
        assert digest.catches[0]["species_identified_by"] == "ai"
        assert digest.catches[0]["ai_confidence"] == 0.6

    async def test_days_back_window(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await catch_repo.create(
                s,
                user.id,
                species_key=normalise("levrek"),
                species_label="Levrek",
                caught_at=datetime.now(UTC) - timedelta(days=200),
                species_confirmed_by_user=True,
            )
            recent = await get_memory_service().catch_digest(s, user.id, days_back=30)
            everything = await get_memory_service().catch_digest(s, user.id)
        assert recent.total == 0
        assert everything.total == 1


class TestSessionDigest:
    async def test_no_sessions_says_so(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            digest = await get_memory_service().session_digest(s, user.id)
        assert digest["sessions"] == []
        assert "do not invent" in digest["instruction_to_model"].lower()

    async def test_identifies_most_productive_session(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            for day, catches in ((20, 1), (10, 3)):
                started = datetime.now(UTC) - timedelta(days=day)
                session = await session_repo.start(
                    s,
                    user.id,
                    target_species=["levrek"],
                    location_label="Bostanlı",
                    started_at=started,
                )
                for i in range(catches):
                    await catch_repo.create(
                        s,
                        user.id,
                        session_id=session.id,
                        species_key=normalise("levrek"),
                        species_label="Levrek",
                        caught_at=started + timedelta(minutes=30 * (i + 1)),
                        species_confirmed_by_user=True,
                    )
                await session_repo.finish(
                    s, user.id, session.id, ended_at=started + timedelta(hours=2)
                )
            digest = await get_memory_service().session_digest(s, user.id)
        assert digest["most_productive_session"]["catches"] == 3


class TestSessionReport:
    async def test_report_contains_the_facts_an_angler_wants(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            started = datetime.now(UTC) - timedelta(hours=2, minutes=17)
            session = await session_repo.start(
                s,
                user.id,
                target_species=["levrek"],
                location_label="Bostanlı",
                started_at=started,
            )
            await session_repo.add_weather_snapshot(
                s,
                session.id,
                {
                    "captured_at": started,
                    "provider": "mock",
                    "summary": "Parçalı bulutlu, rüzgâr GB 14 km/h",
                },
            )
            for i in range(2):
                await catch_repo.create(
                    s,
                    user.id,
                    session_id=session.id,
                    species_key=normalise("levrek"),
                    species_label="Levrek",
                    caught_at=datetime.now(UTC) - timedelta(minutes=20 - i * 10),
                    length_cm=44.0,
                    lure_label="Minnow 11 cm / 11 g",
                    species_confirmed_by_user=True,
                )
            full = await session_repo.get(s, user.id, session.id)
            full.ended_at = datetime.now(UTC)
            report = await get_memory_service().build_session_report(s, full, "tr")

        assert "OTURUM ÖZETİ" in report
        assert "Bostanlı" in report
        assert "2h 17m" in report
        assert "Minnow 11 cm / 11 g" in report
        assert "Av: 2" in report
        # The thing an angler actually cares about: when it happened.
        assert "son bir saatte" in report

    async def test_report_is_deterministic(self, registered, session_factory):
        """Rendered verbatim in the UI and offline, so it must not vary."""
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            started = datetime.now(UTC) - timedelta(hours=1)
            session = await session_repo.start(
                s, user.id, location_label="Bostanlı", started_at=started
            )
            full = await session_repo.get(s, user.id, session.id)
            full.ended_at = started + timedelta(hours=1)
            first = await get_memory_service().build_session_report(s, full, "tr")
            second = await get_memory_service().build_session_report(s, full, "tr")
        assert first == second

    async def test_report_localised(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            session = await session_repo.start(
                s, user.id, started_at=datetime.now(UTC) - timedelta(hours=1)
            )
            full = await session_repo.get(s, user.id, session.id)
            full.ended_at = datetime.now(UTC)
            assert "SESSION SUMMARY" in await get_memory_service().build_session_report(
                s, full, "en"
            )


class TestUserBrief:
    async def test_brief_is_small_and_factual(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            brief = await get_memory_service().user_profile_brief(s, user.id)
        assert brief["total_catches"] == 4
        assert brief["top_species"][0]["species"] == "European seabass"
        assert "Minnow 11 cm / 11 g" in brief["go_to_lures"]
        # It is injected into the model's context on every new session.
        assert len(str(brief)) < 1200


class TestLanguageSelection:
    """English is the product default; Turkish must still be fully available.

    The assistant switches language on the user's cue, so both paths have to
    keep working — a regression here would silently make the app monolingual.
    """

    async def test_default_output_is_english(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(s, user.id, species="levrek")
        # Turkish *input* still resolves; the *output* is English.
        assert digest.species_label == "European seabass"
        assert "evening" in (digest.time_of_day_pattern or "")

    async def test_turkish_still_available_on_request(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await _seed_seabass_history(s, user.id)
            digest = await get_memory_service().catch_digest(
                s, user.id, species="seabass", language="tr"
            )
        assert digest.species_label == "Levrek"
        assert "akşamüstü" in (digest.time_of_day_pattern or "")

    async def test_report_defaults_to_english(self, registered, session_factory):
        from datetime import UTC, datetime, timedelta

        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            started = datetime.now(UTC) - timedelta(hours=1)
            session = await session_repo.start(s, user.id, started_at=started)
            full = await session_repo.get(s, user.id, session.id)
            full.ended_at = datetime.now(UTC)
            assert "SESSION SUMMARY" in await get_memory_service().build_session_report(s, full)
            assert "OTURUM ÖZETİ" in await get_memory_service().build_session_report(s, full, "tr")
