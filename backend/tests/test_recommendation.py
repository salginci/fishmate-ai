"""The decision engine. Pure logic, so it is tested directly."""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

import pytest

from app.db.models import TackleCategory
from app.repositories import catches as catch_repo
from app.repositories import tackle as tackle_repo
from app.repositories import users as user_repo
from app.services.recommendation import (
    STYLE_WEIGHT_RANGE,
    _score_lure,
    _score_rod,
    choose_style,
    get_recommendation_service,
)
from app.services.species import normalise
from app.services.weather import ConditionsSnapshot


def conditions(**over) -> ConditionsSnapshot:
    base = {
        "observed_at": datetime.now(UTC),
        "latitude": 38.46,
        "longitude": 27.09,
        "provider": "mock",
        "wind_speed_kmh": 10.0,
        "wave_height_m": 0.25,
        "cloud_cover_pct": 30.0,
        "is_day": True,
    }
    base.update(over)
    return ConditionsSnapshot(**base)


class TestStyleChoice:
    def test_explicit_request_always_wins(self):
        """The engine advises; it never overrides what the angler said."""
        style, _ = choose_style(conditions(wave_height_m=2.0), normalise("levrek"), "lrf")
        assert style == "lrf"

    def test_rough_water_moves_to_heavier_class(self):
        style, reasons = choose_style(conditions(wave_height_m=1.2, wind_speed_kmh=35), None, None)
        assert style == "spin"
        kinds = {r.kind for r in reasons}
        assert "observation" in kinds and "inference" in kinds

    def test_calm_water_prefers_light_spin(self):
        style, reasons = choose_style(conditions(wave_height_m=0.15, wind_speed_kmh=8), None, None)
        assert style == "light_spin"
        assert any(r.kind == "observation" for r in reasons)

    def test_rough_water_keeps_heavy_class_over_species_prior(self):
        """Controllability beats the species preference when it is rough."""
        style, _ = choose_style(
            conditions(wave_height_m=1.4, wind_speed_kmh=40), normalise("izmarit"), None
        )
        assert style == "spin"

    def test_species_prior_applies_in_calm_water(self):
        style, _ = choose_style(conditions(), normalise("izmarit"), None)
        assert style == "lrf"

    def test_no_conditions_still_yields_a_style(self):
        style, _ = choose_style(None, None, None)
        assert style in STYLE_WEIGHT_RANGE


class TestScoring:
    def _rod(self, lo, hi, **over):
        from app.db.models import TackleItem

        return TackleItem(
            user_id="u",
            category=TackleCategory.ROD.value,
            name=f"rod {lo}-{hi}",
            weight_min_g=lo,
            weight_max_g=hi,
            **over,
        )

    def _lure(self, grams, category=TackleCategory.MINNOW.value, **over):
        from app.db.models import TackleItem

        return TackleItem(
            user_id="u",
            category=category,
            name=f"lure {grams}g",
            weight_min_g=grams,
            weight_max_g=grams,
            **over,
        )

    def test_rod_outside_the_range_is_excluded(self):
        """A 100-200 g surf rod is simply the wrong tool for LRF."""
        assert _score_rod(self._rod(100, 200), "lrf") is None

    def test_matching_rod_scores_higher_than_marginal_one(self):
        good = _score_rod(self._rod(3, 15), "light_spin")
        marginal = _score_rod(self._rod(14, 40), "light_spin")
        assert good is not None and marginal is not None
        assert good.score > marginal.score

    def test_declared_style_boosts_rod(self):
        plain = _score_rod(self._rod(3, 15), "light_spin")
        tagged = _score_rod(self._rod(3, 15, fishing_styles=["light_spin"]), "light_spin")
        assert tagged.score > plain.score

    def test_lure_far_outside_castable_weight_excluded(self):
        assert _score_lure(self._lure(120), "lrf", conditions(), None, {}) is None

    def test_heavier_lure_preferred_in_chop(self):
        rough = conditions(wave_height_m=0.9)
        heavy = _score_lure(self._lure(24), "spin", rough, None, {})
        light = _score_lure(self._lure(8), "spin", rough, None, {})
        assert heavy.score > light.score
        assert any("contact" in r.text_en for r in heavy.reasons)

    def test_lighter_lure_preferred_when_calm(self):
        calm = conditions(wave_height_m=0.1)
        light = _score_lure(self._lure(8), "light_spin", calm, None, {})
        assert any("natural" in r.text_en for r in light.reasons)

    def test_history_bonus_dominates_theory(self):
        """What actually caught fish for this angler outweighs priors."""
        no_history = _score_lure(self._lure(11), "spin", conditions(), None, {})
        with_history = _score_lure(self._lure(11), "spin", conditions(), None, {"lure 11g": 0.45})
        assert with_history.score > no_history.score
        assert any(r.kind == "history" for r in with_history.reasons)

    def test_predator_species_favours_minnow(self):
        minnow = _score_lure(
            self._lure(11, TackleCategory.MINNOW.value),
            "spin",
            conditions(),
            normalise("levrek"),
            {},
        )
        assert any("baitfish" in r.text_en for r in minnow.reasons)

    def test_bottom_feeder_favours_jighead(self):
        jighead = _score_lure(
            self._lure(7, TackleCategory.JIGHEAD.value),
            "light_spin",
            conditions(),
            normalise("çipura"),
            {},
        )
        assert any("bottom" in r.text_en for r in jighead.reasons)


class TestRecommendService:
    async def _stock(self, s, user_id):
        await tackle_repo.create(
            s,
            user_id,
            category=TackleCategory.ROD.value,
            name="Light spin kamış 3-15 g",
            weight_min_g=3,
            weight_max_g=15,
            fishing_styles=["light_spin"],
        )
        await tackle_repo.create(
            s,
            user_id,
            category=TackleCategory.ROD.value,
            name="Spin kamış 7-28 g",
            weight_min_g=7,
            weight_max_g=28,
            fishing_styles=["spin"],
        )
        await tackle_repo.create(
            s,
            user_id,
            category=TackleCategory.MINNOW.value,
            name="Minnow 11 cm / 11 g",
            weight_min_g=11,
            weight_max_g=11,
            fishing_styles=["spin"],
        )
        await tackle_repo.create(
            s,
            user_id,
            category=TackleCategory.JIGHEAD.value,
            name="Jighead 7 g",
            weight_min_g=7,
            weight_max_g=7,
            fishing_styles=["light_spin"],
        )

    async def test_empty_inventory_asks_instead_of_inventing(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            rec = await get_recommendation_service().recommend(
                s, user.id, conditions=conditions(), target_species="levrek"
            )
        assert rec.lure is None
        assert rec.missing_tackle
        payload = rec.to_tool_payload("tr")
        assert "do NOT" in payload["instruction_to_model"]
        assert rec.confidence <= 0.2

    async def test_recommends_only_owned_gear(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            rec = await get_recommendation_service().recommend(
                s,
                user.id,
                conditions=conditions(wave_height_m=1.1, wind_speed_kmh=34),
                target_species="levrek",
            )
        owned = {
            "Light spin kamış 3-15 g",
            "Spin kamış 7-28 g",
            "Minnow 11 cm / 11 g",
            "Jighead 7 g",
        }
        assert rec.rod.name in owned
        assert rec.lure.name in owned
        assert not rec.missing_tackle

    async def test_confidence_drops_without_conditions(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            with_cond = await get_recommendation_service().recommend(
                s, user.id, conditions=conditions(), target_species="levrek"
            )
            without = await get_recommendation_service().recommend(
                s, user.id, conditions=None, target_species="levrek"
            )
        assert without.confidence < with_cond.confidence

    async def test_history_influences_the_choice(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            for i in range(3):
                await catch_repo.create(
                    s,
                    user.id,
                    species_key=normalise("levrek"),
                    species_label="Levrek",
                    caught_at=datetime.now(UTC) - timedelta(days=i + 1),
                    lure_label="Jighead 7 g",
                    species_confirmed_by_user=True,
                )
            rec = await get_recommendation_service().recommend(
                s, user.id, conditions=conditions(), target_species="levrek"
            )
        assert rec.lure.name == "Jighead 7 g"
        assert any(r.kind == "history" for r in rec.reasons)

    async def test_notes_become_an_observation_not_a_measurement(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            rec = await get_recommendation_service().recommend(
                s,
                user.id,
                conditions=conditions(),
                target_species="levrek",
                notes="yüzeyde baitfish hareketi var",
            )
        first = rec.reasons[0]
        assert first.kind == "observation"
        assert "baitfish" in first.text_tr

    async def test_payload_keeps_spoken_answer_short(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            rec = await get_recommendation_service().recommend(
                s, user.id, conditions=conditions(), target_species="levrek"
            )
        assert len(rec.reasons) <= 4
        payload = rec.to_tool_payload("tr")
        assert payload["technique"] and payload["retrieve"]
        assert 0.0 <= payload["confidence"] <= 1.0

    @pytest.mark.parametrize("language", ["tr", "en"])
    async def test_payload_localised(self, registered, session_factory, language):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            await self._stock(s, user.id)
            rec = await get_recommendation_service().recommend(
                s,
                user.id,
                conditions=conditions(),
                target_species="levrek",
                language=language,
            )
        payload = rec.to_tool_payload(language)
        assert payload["technique"]
        assert payload["target_species"] in ("Levrek", "European seabass")


class TestLightLevel:
    """Regression guard for the timezone bug in dusk/dawn classification."""

    def test_midday_is_not_mistaken_for_dawn(self):
        from app.services.recommendation import _light_level

        now = datetime.now(UTC)
        # Local sunrise 3+ hours ago, expressed in a UTC+3 local wall clock.
        local_sunrise = (now + timedelta(hours=3) - timedelta(hours=3, minutes=10)).strftime(
            "%Y-%m-%dT%H:%M"
        )
        snapshot = ConditionsSnapshot(
            observed_at=now,
            latitude=38.46,
            longitude=27.09,
            provider="t",
            sunrise=local_sunrise,
            utc_offset_seconds=10_800,
            cloud_cover_pct=10.0,
            is_day=True,
        )
        level, _ = _light_level(snapshot)
        assert level != "low"

    def test_just_after_sunset_is_low_light(self):
        from app.services.recommendation import _light_level

        now = datetime.now(UTC)
        local_sunset = (now + timedelta(hours=3) - timedelta(minutes=40)).strftime("%Y-%m-%dT%H:%M")
        snapshot = ConditionsSnapshot(
            observed_at=now,
            latitude=38.46,
            longitude=27.09,
            provider="t",
            sunset=local_sunset,
            utc_offset_seconds=10_800,
            is_day=True,
        )
        level, reason = _light_level(snapshot)
        assert level == "low"
        assert "sunset" in reason.text_en

    def test_night_is_dark(self):
        from app.services.recommendation import _light_level

        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=38.46,
            longitude=27.09,
            provider="t",
            is_day=False,
        )
        assert _light_level(snapshot)[0] == "dark"

    def test_heavy_cloud_is_overcast(self):
        from app.services.recommendation import _light_level

        snapshot = ConditionsSnapshot(
            observed_at=datetime.now(UTC),
            latitude=38.46,
            longitude=27.09,
            provider="t",
            cloud_cover_pct=85.0,
            is_day=True,
        )
        assert _light_level(snapshot)[0] == "overcast"

    def test_no_conditions_is_unknown(self):
        from app.services.recommendation import _light_level

        assert _light_level(None) == ("unknown", None)
