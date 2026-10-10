"""Regulations: the no-fabrication guarantee.

A wrong size limit is a fine for the user, so the only acceptable behaviours
are "cited and verified" or "explicitly cannot confirm".
"""

from __future__ import annotations

import json
from unittest.mock import MagicMock

import pytest

from app.config import Settings
from app.services.regulations import (
    GroundedRegulationProvider,
    RegulationService,
    StaticRegulationProvider,
)


def settings(**over) -> Settings:
    base = {"auth_secret": "r" * 48, "gemini_api_key": ""}
    base.update(over)
    return Settings(**base)


class TestNoFabrication:
    async def test_species_outside_the_circular_is_never_verified(self):
        # İskorpit (Scorpaena porcus) has no row in Çizelge 7. The table does
        # list Lipsöz (Scorpaena scrofa) at 15 cm, and borrowing that number
        # for a different fish is exactly the failure this module exists to
        # prevent.
        answer = await RegulationService(settings()).lookup("TR", "iskorpit", "tr")
        assert answer.verified is False
        payload = answer.to_tool_payload("tr")
        assert "NOT VERIFIED" in payload["instruction_to_model"]
        assert "minimum_length_cm" not in payload
        assert "closed_season" not in payload

    async def test_unverified_payload_names_the_authority(self):
        answer = await RegulationService(settings()).lookup("TR", "iskorpit", "tr")
        payload = answer.to_tool_payload("tr")
        assert "Tarım ve Orman" in payload["authority"]["name"]
        assert "teyit edemiyorum" in payload["user_message"]

    async def test_curated_species_is_verified_and_cited(self):
        answer = await RegulationService(settings()).lookup("TR", "çipura", "tr")
        payload = answer.to_tool_payload("tr")
        assert answer.verified is True
        assert payload["minimum_length_cm"] == 20
        assert payload["sources"][0]["url"].startswith("https://")
        assert payload["sources"][0]["effective_from"]

    async def test_akya_and_sarikuyruk_do_not_share_a_limit(self):
        # Two different fish that one catalogue entry used to conflate. The
        # circular puts Akya (Lichia amia) at 40 cm and Sarıkuyruk (Seriola
        # dumerili) at 30 cm, so the mix-up read as "legal" on an undersized
        # akya.
        service = RegulationService(settings())
        akya = await service.lookup("TR", "akya", "tr")
        sarikuyruk = await service.lookup("TR", "sarıkuyruk", "tr")
        assert akya.min_length_cm == 40
        assert sarikuyruk.min_length_cm == 30

    async def test_every_curated_entry_carries_a_citation(self):
        """The invariant that replaced "the file ships empty"."""
        from app.services.regulations import RESOURCES

        for path in RESOURCES.glob("*.json"):
            data = json.loads(path.read_text(encoding="utf-8"))
            for key, entry in (data.get("species") or {}).items():
                assert entry.get("source_url"), f"{path.name}:{key} has no source"
                assert entry.get("effective_from"), f"{path.name}:{key} has no date"

    async def test_general_rules_travel_with_the_answer(self):
        # A correct minimum length on its own is still a fine: the same
        # circular caps the daily take.
        answer = await RegulationService(settings()).lookup("TR", "çipura", "tr")
        rules = answer.to_tool_payload("tr")["general_rules"]
        assert any("5 kg" in rule for rule in rules)

    async def test_unknown_region_still_refuses_cleanly(self):
        answer = await RegulationService(settings()).lookup("XX", "levrek", "en")
        assert answer.verified is False
        assert "can't confirm" in answer.to_tool_payload("en")["user_message"]

    async def test_shipped_data_file_names_its_authority(self):
        from app.services.regulations import RESOURCES

        data = json.loads((RESOURCES / "tr.json").read_text(encoding="utf-8"))
        assert data["authority"]["url"]
        assert data["source"]["url"].startswith("https://")

    async def test_entry_without_citation_is_not_trusted(self, tmp_path, monkeypatch):
        """Curated data is only verified if it carries a source and a date."""
        import app.services.regulations as module

        (tmp_path / "tr.json").write_text(
            json.dumps(
                {
                    "authority": {"name_en": "Test authority", "url": "https://example.gov"},
                    "species": {
                        # Deliberately missing source_url / effective_from.
                        "dicentrarchus_labrax": {"min_length_cm": 25}
                    },
                }
            ),
            encoding="utf-8",
        )
        monkeypatch.setattr(module, "RESOURCES", tmp_path)
        answer = await StaticRegulationProvider().lookup("TR", "dicentrarchus_labrax", "en")
        assert answer.verified is False
        assert answer.min_length_cm is None

    async def test_fully_cited_entry_is_trusted(self, tmp_path, monkeypatch):
        import app.services.regulations as module

        (tmp_path / "tr.json").write_text(
            json.dumps(
                {
                    "authority": {"name_en": "Test authority", "url": "https://example.gov"},
                    "species": {
                        "dicentrarchus_labrax": {
                            "min_length_cm": 25,
                            "closed_season": {"from": "04-15", "to": "06-15"},
                            "source_url": "https://example.gov/circular-2026",
                            "source_title": "Circular 2026/1",
                            "effective_from": "2026-01-01",
                        }
                    },
                }
            ),
            encoding="utf-8",
        )
        monkeypatch.setattr(module, "RESOURCES", tmp_path)
        answer = await StaticRegulationProvider().lookup("TR", "dicentrarchus_labrax", "en")
        assert answer.verified is True
        payload = answer.to_tool_payload("en")
        assert payload["minimum_length_cm"] == 25
        assert payload["sources"][0]["effective_from"] == "2026-01-01"
        assert "cited official source" in payload["instruction_to_model"]


class TestGroundedProvider:
    async def test_disabled_without_api_key(self):
        assert await GroundedRegulationProvider(settings()).lookup("TR", "x", "tr") is None

    async def test_grounded_result_is_still_unverified(self):
        """A search hit is a lead, not an official circular."""
        provider = GroundedRegulationProvider(settings(gemini_api_key="k"))
        response = MagicMock()
        response.text = "Levrek için asgari boy 25 cm olarak belirtilmiştir."
        chunk = MagicMock()
        chunk.web.uri = "https://tarimorman.gov.tr/x"
        chunk.web.title = "Tebliğ"
        candidate = MagicMock()
        candidate.grounding_metadata.grounding_chunks = [chunk]
        response.candidates = [candidate]

        fake_client = MagicMock()
        fake_client.models.generate_content = MagicMock(return_value=response)
        provider._client = fake_client

        answer = await provider.lookup("TR", "dicentrarchus_labrax", "tr")
        assert answer is not None
        assert answer.verified is False
        assert answer.advisory_text
        assert answer.sources[0].url == "https://tarimorman.gov.tr/x"
        payload = answer.to_tool_payload("tr")
        # The number appears only as an unverified search result, never as fact.
        assert "unverified_search_result" in payload
        assert "minimum_length_cm" not in payload
        assert "NOT VERIFIED" in payload["instruction_to_model"]

    async def test_provider_raises_when_the_lookup_cannot_run(self):
        from app.services.regulations import RegulationLookupUnavailable

        provider = GroundedRegulationProvider(settings(gemini_api_key="k"))
        fake_client = MagicMock()
        fake_client.models.generate_content = MagicMock(side_effect=RuntimeError("down"))
        provider._client = fake_client
        with pytest.raises(RegulationLookupUnavailable):
            await provider.lookup("TR", "iskorpit", "tr")

    async def test_service_falls_back_to_refusal_when_grounding_fails(self):
        service = RegulationService(settings(gemini_api_key="k"))
        fake_client = MagicMock()
        fake_client.models.generate_content = MagicMock(side_effect=RuntimeError("down"))
        service.grounded._client = fake_client
        answer = await service.lookup("TR", "iskorpit", "tr")
        assert answer.verified is False
        # A failed lookup says "couldn't check", not "no such rule".
        assert "kontrol edemedim" in answer.to_tool_payload("tr")["user_message"]

    async def test_an_outage_is_not_reported_as_an_absent_rule(self):
        # "I can't reach the source" and "there is no rule" are different
        # facts. Saying the second when the first is true teaches the angler
        # that the feature does not work.
        service = RegulationService(settings(gemini_api_key="k"))
        fake_client = MagicMock()
        fake_client.models.generate_content = MagicMock(
            side_effect=RuntimeError("429 RESOURCE_EXHAUSTED")
        )
        service.grounded._client = fake_client
        answer = await service.lookup("TR", "iskorpit", "tr")
        payload = answer.to_tool_payload("tr")
        assert payload["lookup_unavailable"] is True
        assert "right now" in payload["instruction_to_model"]

    async def test_curated_data_answers_even_when_grounding_is_down(self):
        # The quota outage that started this: with a cited entry on disk the
        # grounded path is never needed.
        service = RegulationService(settings(gemini_api_key="k"))
        fake_client = MagicMock()
        fake_client.models.generate_content = MagicMock(
            side_effect=RuntimeError("429 RESOURCE_EXHAUSTED")
        )
        service.grounded._client = fake_client
        answer = await service.lookup("TR", "çipura", "tr")
        assert answer.verified is True
        assert answer.min_length_cm == 20
