"""Place-name resolution.

The bug these exist for was reported from a real session: asked about the
weather at Bostanlı, the assistant said it could not check it. It had no way to
turn a name into coordinates. The fix is only half a geocoder call, though —
the other half is choosing *which* Bostanlı, because the obvious answer is
wrong in a way that would have the assistant describe the sea state of a
village in Adıyaman.
"""

from __future__ import annotations

from app.repositories import users as user_repo
from app.services.geocoding import (
    MockGeocodingProvider,
    NullGeocodingProvider,
    Place,
    distance_km,
    resolve,
)
from app.tools.registry import ToolContext, get_tool_registry

# Measured, not invented: these are the coordinates Nominatim actually returns.
IZMIR_BOSTANLI = Place(
    "Bostanlı Mahallesi", "Bostanlı Mahallesi, Karşıyaka, İzmir", 38.4570, 27.0984, region="İzmir"
)
ADIYAMAN_BOSTANLI = Place("Bostanlı", "Bostanlı, Adıyaman", 37.8133, 38.8932, region="Adıyaman")
AFYON_BOSTANLI = Place("Bostanlı", "Bostanlı, Afyonkarahisar", 38.9422, 30.6364, region="Afyon")
IGDIR_BOSTANLI = Place("Bostanlı", "Bostanlı, Iğdır", 40.0311, 43.3768, region="Iğdır")

#: An angler standing on the İzmir waterfront.
IZMIR = (38.42, 27.14)


class TestDistance:
    def test_known_separation(self):
        # İzmir to the Adıyaman namesake, the pair the ranking has to separate.
        km = distance_km(*IZMIR, ADIYAMAN_BOSTANLI.latitude, ADIYAMAN_BOSTANLI.longitude)
        assert 1000 < km < 1060

    def test_zero_distance(self):
        assert distance_km(38.42, 27.14, 38.42, 27.14) == 0


class TestResolve:
    def test_proximity_beats_the_geocoder_ranking(self):
        """The case that motivated all of this.

        Nominatim returned the İzmir neighbourhood *tenth of twelve* for
        "Bostanlı", behind villages 300-1400 km away, because global importance
        has nothing to do with which one this angler means. Re-ranking by
        distance from the angler puts it first.
        """
        candidates = [ADIYAMAN_BOSTANLI, IGDIR_BOSTANLI, AFYON_BOSTANLI, IZMIR_BOSTANLI]
        resolution = resolve(candidates, IZMIR)
        assert resolution.place is not None
        assert resolution.place.region == "İzmir"
        assert not resolution.ambiguous

    def test_no_reference_point_means_asking_not_guessing(self):
        candidates = [ADIYAMAN_BOSTANLI, AFYON_BOSTANLI, IZMIR_BOSTANLI]
        resolution = resolve(candidates, None)
        assert resolution.ambiguous
        assert resolution.place is None
        assert len(resolution.candidates) == 3

    def test_a_single_candidate_needs_no_reference_point(self):
        resolution = resolve([IZMIR_BOSTANLI], None)
        assert resolution.place is IZMIR_BOSTANLI

    def test_two_far_namesakes_with_neither_nearby_is_ambiguous(self):
        """Nearest is not good enough on its own.

        Both of these are hundreds of kilometres from the angler, so "nearest"
        carries no information and picking one would be a coin toss dressed up
        as an answer.
        """
        resolution = resolve([ADIYAMAN_BOSTANLI, IGDIR_BOSTANLI], IZMIR)
        assert resolution.ambiguous

    def test_two_candidates_a_similar_distance_away_is_ambiguous(self):
        """The guard is a *ratio*, not just a radius.

        Two spots both about 20 km away in opposite directions are equally
        plausible, and the nearer one being marginally nearer means nothing.
        """
        near_a = Place("Spot", "Spot A", 38.60, 27.14, region="A")
        near_b = Place("Spot", "Spot B", 38.24, 27.14, region="B")
        resolution = resolve([near_a, near_b], IZMIR)
        assert resolution.ambiguous

    def test_nothing_found(self):
        resolution = resolve([], IZMIR)
        assert resolution.empty
        assert not resolution.ambiguous

    def test_suggestions_are_capped_so_they_can_be_read_aloud(self):
        many = [
            Place(f"Spot {i}", f"Spot {i}", 30.0 + i, 40.0 + i, region=str(i)) for i in range(9)
        ]
        resolution = resolve(many, None)
        assert len(resolution.candidates) <= 4


class TestNullProvider:
    async def test_geocoding_switched_off_degrades_quietly(self):
        provider = NullGeocodingProvider()
        assert await provider.search("Bostanlı") == []
        assert await provider.reverse(38.4, 27.1) is None


class TestFindPlaceTool:
    """The tool the model calls. This is the user-visible fix."""

    async def test_named_spot_resolves_to_coordinates(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "find_place",
                {"name": "Bostanlı"},
                # The angler's device position: this is what disambiguates.
                ToolContext(db=s, user=user, latitude=IZMIR[0], longitude=IZMIR[1]),
            )
        assert result.ok, result.payload
        place = result.payload["place"]
        assert "Bostanlı" in place["name"]
        assert place["region"] == "İzmir"
        assert abs(place["latitude"] - 38.4570) < 0.01
        # And the model is told what to do with it.
        assert "get_current_conditions" in result.payload["instruction_to_model"]

    async def test_distance_from_the_angler_is_reported(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "find_place",
                {"name": "Kilyos"},
                ToolContext(db=s, user=user, latitude=IZMIR[0], longitude=IZMIR[1]),
            )
        # Kilyos is on the Black Sea, İzmir is on the Aegean: a long way.
        assert result.payload["km_from_user"] > 300

    async def test_an_ambiguous_name_asks_instead_of_guessing(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            # No device position and no saved spot, so nothing to rank by.
            user.preferences.location_precision = "none"
            result = await get_tool_registry().execute(
                "find_place", {"name": "Bostanlı"}, ToolContext(db=s, user=user)
            )
        assert result.payload.get("ambiguous") is True
        assert len(result.payload["options"]) > 1
        instruction = result.payload["instruction_to_model"]
        assert "ask which one" in instruction.lower()
        assert "do not report any conditions" in instruction.lower()

    async def test_unknown_place_refuses_to_invent_one(self, registered, session_factory):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "find_place", {"name": "nowhere"}, ToolContext(db=s, user=user)
            )
        assert result.payload["error"] == "place_not_found"
        assert "do not guess" in result.payload["instruction_to_model"].lower()

    async def test_a_name_too_short_to_search_is_rejected_cleanly(
        self, registered, session_factory
    ):
        async with session_factory() as s:
            user = await user_repo.get_by_id(s, registered["user"]["id"])
            result = await get_tool_registry().execute(
                "find_place", {"name": "a"}, ToolContext(db=s, user=user)
            )
        assert not result.ok
        assert result.error_code == "invalid_arguments"


class TestMockProviderFixtures:
    async def test_the_mock_carries_the_real_ambiguity(self):
        """Otherwise the ranking is tested against a problem it never had."""
        places = await MockGeocodingProvider().search("Bostanlı")
        regions = {p.region for p in places}
        assert "İzmir" in regions
        assert len(regions) > 1, "the inland namesakes are the whole point"
