"""Pydantic argument models for every Gemini tool.

Model-generated arguments are untrusted input. Each tool has an explicit model
with bounds, so a hallucinated `limit: 100000` or a bogus enum value is a clean
validation failure the model can recover from, never a database full-scan.

`extra="forbid"` is intentional: if the model invents a parameter we have not
declared, that is a signal our declaration is ambiguous, and we would rather
see the error than silently drop it.
"""

from __future__ import annotations

import datetime as dt

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

_STYLES = ("lrf", "light_spin", "spin", "surf", "bottom", "float")
_CATEGORIES = (
    "rod",
    "reel",
    "line",
    "leader",
    "lure",
    "jighead",
    "hook",
    "soft_plastic",
    "metal_jig",
    "minnow",
    "bait",
    "accessory",
)
_OBSERVATION_KINDS = (
    "user_note",
    "ai_observation",
    "bite",
    "missed_strike",
    "condition_change",
)


class ToolArgs(BaseModel):
    # populate_by_name lets a field be named differently in Python than in
    # the declaration the model sees (see GetSunTimesArgs.on_date).
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True, populate_by_name=True)


class _Located(ToolArgs):
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)


class GetCurrentConditionsArgs(_Located):
    pass


class FindPlaceArgs(ToolArgs):
    name: str = Field(min_length=2, max_length=120)


class GetConditionsForecastArgs(_Located):
    hours: int = Field(default=6, ge=1, le=24)


class GetSunTimesArgs(_Located):
    #: Declared to Gemini as "date"; aliased because `date` would shadow
    #: the type at class-definition time.
    on_date: dt.date | None = Field(default=None, alias="date")


class GetTackleInventoryArgs(ToolArgs):
    category: str | None = None
    fishing_style: str | None = None

    @field_validator("category")
    @classmethod
    def _valid_category(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.lower()
        if v not in _CATEGORIES:
            raise ValueError(f"category must be one of: {', '.join(_CATEGORIES)}")
        return v

    @field_validator("fishing_style")
    @classmethod
    def _valid_style(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.lower()
        if v not in _STYLES:
            raise ValueError(f"fishing_style must be one of: {', '.join(_STYLES)}")
        return v


class AddTackleArgs(ToolArgs):
    """One item the user said they own.

    Bounded like [LogCatchArgs]: a 40 m rod or a 9 kg lure is a transcription
    error, and writing it into the angler's own inventory would quietly poison
    every recommendation that reads it afterwards.
    """

    category: str
    name: str = Field(min_length=1, max_length=120)
    brand: str | None = Field(default=None, max_length=80)
    weight_min_g: float | None = Field(default=None, gt=0, le=2_000)
    weight_max_g: float | None = Field(default=None, gt=0, le=2_000)
    length_cm: float | None = Field(default=None, gt=0, le=400)
    line_rating: str | None = Field(default=None, max_length=32)
    colour: str | None = Field(default=None, max_length=40)
    notes: str | None = Field(default=None, max_length=1000)

    @field_validator("category")
    @classmethod
    def _valid_category(cls, v: str) -> str:
        v = v.lower()
        if v not in _CATEGORIES:
            raise ValueError(f"category must be one of: {', '.join(_CATEGORIES)}")
        return v

    @model_validator(mode="after")
    def _ordered_weights(self) -> AddTackleArgs:
        low, high = self.weight_min_g, self.weight_max_g
        if low is not None and high is not None and low > high:
            # Said the other way round ("eight to one grams") rather than
            # wrong, and a reversed range reads as "no match" to every scorer
            # that compares against it.
            self.weight_min_g, self.weight_max_g = high, low
        return self


class GetRecentCatchesArgs(ToolArgs):
    species: str | None = Field(default=None, max_length=80)
    limit: int = Field(default=5, ge=1, le=20)
    days_back: int | None = Field(default=None, ge=1, le=3650)


class GetRecentSessionsArgs(ToolArgs):
    limit: int = Field(default=5, ge=1, le=20)
    species: str | None = Field(default=None, max_length=80)


class RecommendSetupArgs(_Located):
    target_species: str | None = Field(default=None, max_length=80)
    fishing_style: str | None = None
    notes: str | None = Field(default=None, max_length=500)

    @field_validator("fishing_style")
    @classmethod
    def _valid_style(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.lower()
        if v not in _STYLES:
            raise ValueError(f"fishing_style must be one of: {', '.join(_STYLES)}")
        return v


class StartFishingSessionArgs(_Located):
    target_species: list[str] = Field(default_factory=list, max_length=6)
    location_label: str | None = Field(default=None, max_length=160)
    fishing_style: str | None = None

    @field_validator("fishing_style")
    @classmethod
    def _valid_style(cls, v: str | None) -> str | None:
        if v is None:
            return None
        v = v.lower()
        if v not in _STYLES:
            raise ValueError(f"fishing_style must be one of: {', '.join(_STYLES)}")
        return v

    @field_validator("target_species")
    @classmethod
    def _clean_species(cls, v: list[str]) -> list[str]:
        return [s.strip() for s in v if s and s.strip()][:6]


class FinishFishingSessionArgs(ToolArgs):
    notes: str | None = Field(default=None, max_length=1000)


class LogCatchArgs(ToolArgs):
    species: str = Field(min_length=1, max_length=80)
    ai_confidence: float | None = Field(default=None, ge=0.0, le=1.0)
    ai_reasoning: str | None = Field(default=None, max_length=500)
    # Generous but finite bounds: a 4 m shore fish is a transcription error,
    # and silently storing it would corrupt the user's own statistics.
    length_cm: float | None = Field(default=None, gt=0, le=400)
    weight_g: float | None = Field(default=None, gt=0, le=200_000)
    lure_label: str | None = Field(default=None, max_length=120)
    technique: str | None = Field(default=None, max_length=120)
    released: bool = True
    notes: str | None = Field(default=None, max_length=1000)


class AddSessionObservationArgs(ToolArgs):
    kind: str
    text: str = Field(min_length=1, max_length=600)

    @field_validator("kind")
    @classmethod
    def _valid_kind(cls, v: str) -> str:
        v = v.lower()
        if v not in _OBSERVATION_KINDS:
            raise ValueError(f"kind must be one of: {', '.join(_OBSERVATION_KINDS)}")
        return v


class GetFishingRegulationsArgs(ToolArgs):
    species: str | None = Field(default=None, max_length=80)
    region: str | None = Field(default=None, max_length=8)

    @field_validator("region")
    @classmethod
    def _upper(cls, v: str | None) -> str | None:
        return v.upper() if v else None
