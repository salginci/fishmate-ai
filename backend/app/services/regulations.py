"""Fishing-regulation lookup.

This module exists to make a specific failure *impossible*: the assistant
stating a minimum length, closed season or protected status that nobody
verified. A wrong number here is not a bad answer, it is a fine or a
confiscated rod for the user.

So the contract is inverted from the rest of the app — the default answer is
"I cannot confirm this", and `verified=True` is only ever set for data that
arrived with a citation and an effective date.

Two providers, tried in order:

1. `StaticRegulationProvider` — a curated JSON file per region, transcribed
   from the official circular, each entry carrying its own `source_url` and
   `effective_from`. `tr.json` holds Çizelge 7 of circular 2024/21; a species
   the circular does not list is left out, so the tool still refuses for it.
2. `GroundedRegulationProvider` — asks Gemini with Google Search grounding and
   returns the grounded text *with its sources*, still flagged unverified, so
   the model can relay it while telling the user to confirm officially. It
   needs its own search quota and has been seen to 429 for days at a time, so
   it is a bonus, never the thing the answer depends on.

Neither path ever lets the model answer from its own parameters.
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, Protocol

from app.config import Settings, get_settings
from app.core.observability import get_logger, metrics
from app.services import species as species_catalogue

log = get_logger("regulations")


class RegulationLookupUnavailable(RuntimeError):
    """The lookup could not run — a quota, a network fault, a bad key.

    Distinct from "there is no verified rule for this species", which is a
    fact about the data. Conflating them makes a temporary outage sound like
    a permanent absence, and the angler stops asking.
    """


RESOURCES = Path(__file__).resolve().parent.parent / "resources" / "regulations"


@dataclass
class RegulationSource:
    title: str | None = None
    url: str | None = None
    effective_from: str | None = None


@dataclass
class RegulationAnswer:
    """A regulation response that is explicit about its own trustworthiness."""

    region: str
    species_key: str | None
    species_label: str | None
    #: True ONLY for curated data with a citation and effective date.
    verified: bool = False
    min_length_cm: float | None = None
    closed_season: dict[str, str] | None = None
    daily_limit: int | None = None
    protected: bool | None = None
    #: Free text from a grounded lookup. Unverified by construction.
    advisory_text: str | None = None
    sources: list[RegulationSource] = field(default_factory=list)
    authority_name: str | None = None
    authority_url: str | None = None
    #: Region-wide rules from the same circular: the weight cap, the tolerance,
    #: the limit on count-limited species. They apply to every answer, and
    #: leaving them out makes a correct minimum length sound like the whole
    #: rule -- an angler who keeps thirty legal-sized çipura is still fined.
    general_rules: list[str] = field(default_factory=list)
    #: True when the *lookup* failed rather than the data being absent. The two
    #: deserve different sentences: "I can't reach the source right now" is a
    #: reason to try again, "there is no verified rule" is not.
    lookup_unavailable: bool = False
    checked_at: str = ""

    def to_tool_payload(self, language: str = "en") -> dict[str, Any]:
        """functionResponse payload.

        `instruction_to_model` is included deliberately: it is the last line of
        defence, restating at call time what the system instruction already
        says, so an unverified answer cannot be narrated as fact.
        """
        tr = language.startswith("tr")
        payload: dict[str, Any] = {
            "region": self.region,
            "species": self.species_label,
            "verified": self.verified,
            "checked_at": self.checked_at or datetime.now(UTC).isoformat(),
        }
        if self.general_rules:
            payload["general_rules"] = self.general_rules
        if self.verified:
            for key, value in (
                ("minimum_length_cm", self.min_length_cm),
                ("daily_limit", self.daily_limit),
                ("protected", self.protected),
                ("closed_season", self.closed_season),
            ):
                if value is not None:
                    payload[key] = value
            payload["instruction_to_model"] = (
                "This data is from a cited official source. State it together with "
                "its effective date, and mention that rules can change."
            )
        elif self.lookup_unavailable:
            payload["lookup_unavailable"] = True
            payload["instruction_to_model"] = (
                "The regulation lookup itself failed; this is not a statement that "
                "no rule exists. Say in one short sentence that you cannot check it "
                "right now and that they can try again in a moment, and point at the "
                "authority named here. Do NOT state any size limit, closed season, "
                "quota or protected status."
            )
            payload["user_message"] = (
                "Mevzuatı şu an kontrol edemedim — birazdan tekrar deneyebilirsin. "
                f"Kesin bilgi için {self.authority_name or 'yetkili kurumun'} "
                "güncel tebliğine bakabilirsin."
                if tr
                else "I couldn't check the rules just now — try again in a moment. "
                f"For certainty, see the current circular from "
                f"{self.authority_name or 'the responsible authority'}."
            )
        else:
            payload["instruction_to_model"] = (
                "NOT VERIFIED. Do NOT state any size limit, closed season, quota or "
                "protected status as fact. Tell the user plainly that you cannot "
                "confirm it and refer them to the official authority named here."
            )
            payload["user_message"] = (
                "Bunu doğrulanmış bir kaynaktan teyit edemiyorum. Güncel tebliğ için "
                f"{self.authority_name or 'yetkili kurumun'} yayınına bakman gerekir."
                if tr
                else "I can't confirm this from a verified source. Check the current "
                f"circular from {self.authority_name or 'the responsible authority'}."
            )
        if self.advisory_text:
            payload["unverified_search_result"] = self.advisory_text
        if self.sources:
            payload["sources"] = [
                {
                    k: v
                    for k, v in (
                        ("title", s.title),
                        ("url", s.url),
                        ("effective_from", s.effective_from),
                    )
                    if v
                }
                for s in self.sources
            ]
        if self.authority_name:
            payload["authority"] = {"name": self.authority_name, "url": self.authority_url}
        return payload


class RegulationProvider(Protocol):
    name: str

    async def lookup(
        self, region: str, species_key: str | None, language: str
    ) -> RegulationAnswer | None: ...


class StaticRegulationProvider:
    """Curated, cited regulation data from `app/resources/regulations/<region>.json`."""

    name = "static"

    def __init__(self) -> None:
        self._cache: dict[str, dict] = {}

    def _load(self, region: str) -> dict | None:
        region = (region or "TR").upper()
        if region in self._cache:
            return self._cache[region]
        path = RESOURCES / f"{region.lower()}.json"
        if not path.exists():
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            log.error("regulations.static_load_failed", region=region, error=str(exc))
            return None
        self._cache[region] = data
        return data

    async def lookup(
        self, region: str, species_key: str | None, language: str
    ) -> RegulationAnswer | None:
        data = self._load(region)
        if data is None:
            return None
        authority = data.get("authority") or {}
        tr = language.startswith("tr")
        answer = RegulationAnswer(
            region=(region or "TR").upper(),
            species_key=species_key,
            species_label=(
                species_catalogue.label_for(species_key, language) if species_key else None
            ),
            authority_name=authority.get("name_tr" if tr else "name_en")
            or authority.get("name_en"),
            authority_url=authority.get("url"),
            general_rules=list((data.get("general_rules") or {}).get("tr" if tr else "en") or []),
            checked_at=datetime.now(UTC).isoformat(),
        )
        entry = (data.get("species") or {}).get(species_key or "")
        if not entry:
            # Known region, no curated entry -> unverified, with the authority.
            return answer
        # Only a fully cited entry may be reported as verified.
        if not entry.get("source_url") or not entry.get("effective_from"):
            log.warning("regulations.entry_missing_citation", species=species_key)
            return answer
        answer.verified = True
        answer.min_length_cm = entry.get("min_length_cm")
        answer.closed_season = entry.get("closed_season")
        answer.daily_limit = entry.get("daily_limit")
        answer.protected = entry.get("protected")
        answer.sources = [
            RegulationSource(
                title=entry.get("source_title"),
                url=entry.get("source_url"),
                effective_from=entry.get("effective_from"),
            )
        ]
        metrics.incr("regulations.verified_hit")
        return answer


class GroundedRegulationProvider:
    """Google-Search-grounded lookup via Gemini, returned *with* its sources.

    Still `verified=False`: a search result is a lead, not an official
    circular. Its value is giving the user a current pointer instead of a
    shrug, while the payload's `instruction_to_model` keeps the model from
    asserting it.
    """

    name = "grounded"

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self._client: Any | None = None

    def _get_client(self) -> Any:
        if self._client is None:
            from google import genai

            self._client = genai.Client(api_key=self.settings.gemini_api_key)
        return self._client

    async def lookup(
        self, region: str, species_key: str | None, language: str
    ) -> RegulationAnswer | None:
        if not self.settings.live_api_configured:
            return None
        from google.genai import types

        label = species_catalogue.label_for(species_key, language) if species_key else None
        region = (region or "TR").upper()
        question = (
            f"Amatör balıkçılıkta {label or 'balık türleri'} için {region} mevzuatında "
            "güncel asgari avlanabilir boy, av yasağı dönemi ve koruma durumu nedir? "
            "Yalnızca resmî kaynaklara dayan ve kaynağın tarihini belirt."
            if language.startswith("tr")
            else f"What are the current recreational fishing rules in {region} for "
            f"{label or 'common species'} — minimum landing size, closed season, "
            "protected status? Use official sources only and state the source date."
        )
        try:
            response = await asyncio.to_thread(
                self._get_client().models.generate_content,
                model=self.settings.gemini_text_model,
                contents=question,
                config=types.GenerateContentConfig(
                    tools=[types.Tool(google_search=types.GoogleSearch())],
                    temperature=0.0,
                ),
            )
        except Exception as exc:
            # The message, not just the class. This path failed in the field
            # with a 429 on the Google Search grounding quota, and a log line
            # carrying only "ClientError" cost an afternoon to tell apart from
            # a bad key, a bad model name and a network fault.
            log.warning(
                "regulations.grounded_failed",
                error_type=type(exc).__name__,
                error=str(exc)[:300],
            )
            metrics.incr("regulations.grounded_failed")
            raise RegulationLookupUnavailable(str(exc)) from exc

        text = (getattr(response, "text", None) or "").strip()
        if not text:
            return None

        sources: list[RegulationSource] = []
        try:
            candidates = getattr(response, "candidates", None) or []
            for candidate in candidates:
                meta = getattr(candidate, "grounding_metadata", None)
                for chunk in getattr(meta, "grounding_chunks", None) or []:
                    web = getattr(chunk, "web", None)
                    if web is not None and getattr(web, "uri", None):
                        sources.append(
                            RegulationSource(title=getattr(web, "title", None), url=web.uri)
                        )
        except Exception:  # pragma: no cover - metadata shape is advisory only
            log.debug("regulations.grounding_metadata_unreadable")

        # De-duplicate while preserving order, cap to keep the payload small.
        seen: set[str] = set()
        unique: list[RegulationSource] = []
        for s in sources:
            if s.url and s.url not in seen:
                seen.add(s.url)
                unique.append(s)

        metrics.incr("regulations.grounded_hit")
        return RegulationAnswer(
            region=region,
            species_key=species_key,
            species_label=label,
            verified=False,
            advisory_text=text[:1500],
            sources=unique[:5],
            checked_at=datetime.now(UTC).isoformat(),
        )


class RegulationService:
    """Tries curated data first, then a grounded lookup, then an honest refusal."""

    def __init__(self, settings: Settings | None = None) -> None:
        self.settings = settings or get_settings()
        self.static = StaticRegulationProvider()
        self.grounded = GroundedRegulationProvider(self.settings)

    async def lookup(
        self,
        region: str = "TR",
        species: str | None = None,
        language: str = "en",
        allow_grounded: bool = True,
    ) -> RegulationAnswer:
        species_key = species_catalogue.normalise(species) if species else None
        static = await self.static.lookup(region, species_key, language)
        if static is not None and static.verified:
            return static

        unavailable = False
        if allow_grounded:
            try:
                grounded = await self.grounded.lookup(region, species_key, language)
            except RegulationLookupUnavailable:
                # Not fatal: the curated file may still answer, and if it does
                # not, the refusal should say "could not check" rather than
                # "no such rule".
                grounded = None
                unavailable = True
            if grounded is not None:
                # Carry the authority details from the static file across.
                if static is not None:
                    grounded.authority_name = static.authority_name
                    grounded.authority_url = static.authority_url
                    grounded.general_rules = static.general_rules
                return grounded

        if static is not None:
            static.lookup_unavailable = unavailable
            return static
        return RegulationAnswer(
            region=(region or "TR").upper(),
            species_key=species_key,
            species_label=(
                species_catalogue.label_for(species_key, language) if species_key else None
            ),
            verified=False,
            checked_at=datetime.now(UTC).isoformat(),
        )


_service: RegulationService | None = None


def get_regulation_service(settings: Settings | None = None) -> RegulationService:
    global _service
    if _service is None:
        _service = RegulationService(settings)
    return _service


def set_regulation_service(service: RegulationService | None) -> None:
    global _service
    _service = service
