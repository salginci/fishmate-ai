"""Request/response contracts for the HTTP API.

Response models are explicit (rather than returning ORM objects) so that
adding a column to the database never silently widens the API surface — in
particular, `hashed_password` and precise coordinates cannot leak by accident.
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, EmailStr, Field, field_validator


class ORMModel(BaseModel):
    model_config = ConfigDict(from_attributes=True)


# ---------------------------------------------------------------------------
# Errors
# ---------------------------------------------------------------------------
class ErrorBody(BaseModel):
    code: str
    message: str
    details: dict[str, Any] | None = None


class ErrorResponse(BaseModel):
    error: ErrorBody


# ---------------------------------------------------------------------------
# Auth
# ---------------------------------------------------------------------------
class RegisterRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=8, max_length=72)
    display_name: str = Field(min_length=1, max_length=80)
    language: str = Field(default="en", max_length=8)


class LoginRequest(BaseModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=72)


class TokenResponse(BaseModel):
    access_token: str
    token_type: str = "bearer"  # noqa: S105 - OAuth scheme name, not a secret
    expires_in: int
    user: UserResponse


class PreferencesResponse(ORMModel):
    language: str
    units: str
    location_precision: str
    default_target_species: list[str] = Field(default_factory=list)
    default_fishing_style: str | None = None
    voice_name: str | None = None
    verbosity: str
    camera_fps: float
    share_anonymous_stats: bool


class PreferencesUpdate(BaseModel):
    language: str | None = Field(default=None, max_length=8)
    units: str | None = None
    location_precision: str | None = None
    default_target_species: list[str] | None = None
    default_fishing_style: str | None = None
    voice_name: str | None = Field(default=None, max_length=32)
    verbosity: str | None = None
    camera_fps: float | None = Field(default=None, ge=0.1, le=2.0)
    share_anonymous_stats: bool | None = None

    @field_validator("location_precision")
    @classmethod
    def _valid_precision(cls, v: str | None) -> str | None:
        if v is None:
            return None
        allowed = {"precise", "approximate", "manual", "none"}
        if v not in allowed:
            raise ValueError(f"location_precision must be one of {sorted(allowed)}")
        return v

    @field_validator("verbosity")
    @classmethod
    def _valid_verbosity(cls, v: str | None) -> str | None:
        if v is None:
            return None
        allowed = {"terse", "concise", "detailed"}
        if v not in allowed:
            raise ValueError(f"verbosity must be one of {sorted(allowed)}")
        return v


class UserResponse(ORMModel):
    id: str
    email: str
    display_name: str
    created_at: datetime
    preferences: PreferencesResponse | None = None


# ---------------------------------------------------------------------------
# Live credentials
# ---------------------------------------------------------------------------
class LiveSessionRequest(BaseModel):
    language: str | None = Field(default=None, max_length=8)
    voice_name: str | None = Field(default=None, max_length=32)
    #: Client tells us whether it will send video, purely so we can log the
    #: expected session-duration ceiling. It does not change the token.
    expects_video: bool = False
    #: Where the angler is, so the session can open knowing the spot instead
    #: of asking. Optional, and coarsened or dropped server-side according to
    #: the user's location-precision consent before it is used for anything.
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)


class TranslateRequest(BaseModel):
    """One finalised transcript line to translate for the subtitle track."""

    text: str = Field(min_length=1, max_length=2000)
    target_language: str = Field(default="tr", max_length=8)
    #: "auto" lets the model detect it, which is the normal case since either
    #: side of the conversation may switch language mid-session.
    source_language: str = Field(default="auto", max_length=8)


class TranslateResponse(BaseModel):
    text: str
    target_language: str


class LiveSessionResponse(BaseModel):
    """Everything the device needs to open a Live WebSocket.

    `setup` is the exact first message to send. It mirrors the configuration
    locked into the token, so the client does not (and cannot) choose its own.
    """

    token: str
    ws_url: str
    model: str
    expires_at: datetime
    new_session_expires_at: datetime
    setup: dict[str, Any]
    input_sample_rate_hz: int
    output_sample_rate_hz: int
    input_audio_mime: str
    #: Primed memory for a fresh conversation (small by design).
    user_brief: dict[str, Any] | None = None


# ---------------------------------------------------------------------------
# Tackle
# ---------------------------------------------------------------------------
class TackleItemBase(BaseModel):
    category: str
    name: str = Field(min_length=1, max_length=120)
    brand: str | None = Field(default=None, max_length=80)
    model: str | None = Field(default=None, max_length=80)
    weight_min_g: float | None = Field(default=None, ge=0, le=1000)
    weight_max_g: float | None = Field(default=None, ge=0, le=1000)
    length_cm: float | None = Field(default=None, ge=0, le=1000)
    line_rating: str | None = Field(default=None, max_length=32)
    colour: str | None = Field(default=None, max_length=40)
    fishing_styles: list[str] | None = None
    target_species: list[str] | None = None
    notes: str | None = Field(default=None, max_length=2000)
    is_favourite: bool = False

    @field_validator("category")
    @classmethod
    def _valid_category(cls, v: str) -> str:
        allowed = {
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
        }
        if v not in allowed:
            raise ValueError(f"category must be one of {sorted(allowed)}")
        return v


class TackleItemCreate(TackleItemBase):
    ai_identified: bool = False


class TackleItemUpdate(BaseModel):
    category: str | None = None
    name: str | None = Field(default=None, min_length=1, max_length=120)
    brand: str | None = Field(default=None, max_length=80)
    model: str | None = Field(default=None, max_length=80)
    weight_min_g: float | None = Field(default=None, ge=0, le=1000)
    weight_max_g: float | None = Field(default=None, ge=0, le=1000)
    length_cm: float | None = Field(default=None, ge=0, le=1000)
    line_rating: str | None = Field(default=None, max_length=32)
    colour: str | None = Field(default=None, max_length=40)
    fishing_styles: list[str] | None = None
    target_species: list[str] | None = None
    notes: str | None = Field(default=None, max_length=2000)
    is_favourite: bool | None = None
    is_active: bool | None = None


class TackleItemResponse(ORMModel):
    id: str
    category: str
    name: str
    brand: str | None = None
    model: str | None = None
    weight_min_g: float | None = None
    weight_max_g: float | None = None
    length_cm: float | None = None
    line_rating: str | None = None
    colour: str | None = None
    fishing_styles: list[str] | None = None
    target_species: list[str] | None = None
    notes: str | None = None
    is_favourite: bool
    is_active: bool
    ai_identified: bool
    created_at: datetime


# ---------------------------------------------------------------------------
# Sessions
# ---------------------------------------------------------------------------
class SessionStartRequest(BaseModel):
    target_species: list[str] = Field(default_factory=list, max_length=6)
    location_label: str | None = Field(default=None, max_length=160)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    fishing_style: str | None = None
    tackle_used: list[str] = Field(default_factory=list)


class SessionFinishRequest(BaseModel):
    notes: str | None = Field(default=None, max_length=2000)


class SessionUpdateRequest(BaseModel):
    target_species: list[str] | None = None
    location_label: str | None = Field(default=None, max_length=160)
    fishing_style: str | None = None
    tackle_used: list[str] | None = None
    notes: str | None = Field(default=None, max_length=2000)


class ObservationCreate(BaseModel):
    kind: str
    text: str = Field(min_length=1, max_length=600)

    @field_validator("kind")
    @classmethod
    def _valid_kind(cls, v: str) -> str:
        allowed = {
            "user_note",
            "ai_observation",
            "bite",
            "missed_strike",
            "condition_change",
        }
        if v not in allowed:
            raise ValueError(f"kind must be one of {sorted(allowed)}")
        return v


class ObservationResponse(ORMModel):
    id: str
    kind: str
    text: str
    observed_at: datetime
    from_ai: bool


class WeatherSnapshotResponse(ORMModel):
    captured_at: datetime
    provider: str
    air_temp_c: float | None = None
    water_temp_c: float | None = None
    wind_speed_kmh: float | None = None
    wind_gust_kmh: float | None = None
    wind_direction_deg: float | None = None
    wave_height_m: float | None = None
    summary: str | None = None


class SessionResponse(ORMModel):
    id: str
    status: str
    started_at: datetime
    ended_at: datetime | None = None
    location_label: str | None = None
    target_species: list[str] | None = None
    fishing_style: str | None = None
    tackle_used: list[str] | None = None
    notes: str | None = None
    summary: str | None = None
    catch_count: int


class SessionDetailResponse(SessionResponse):
    observations: list[ObservationResponse] = Field(default_factory=list)
    weather_snapshots: list[WeatherSnapshotResponse] = Field(default_factory=list)
    catches: list[CatchResponse] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Catches
# ---------------------------------------------------------------------------
class CatchCreate(BaseModel):
    species: str = Field(min_length=1, max_length=80)
    session_id: str | None = None
    length_cm: float | None = Field(default=None, gt=0, le=400)
    weight_g: float | None = Field(default=None, gt=0, le=200_000)
    lure_label: str | None = Field(default=None, max_length=120)
    lure_tackle_id: str | None = None
    rod_tackle_id: str | None = None
    technique: str | None = Field(default=None, max_length=120)
    retrieve_style: str | None = Field(default=None, max_length=120)
    released: bool = True
    notes: str | None = Field(default=None, max_length=2000)
    caught_at: datetime | None = None
    ai_confidence: float | None = Field(default=None, ge=0, le=1)
    ai_reasoning: str | None = Field(default=None, max_length=500)
    photo_local_uri: str | None = Field(default=None, max_length=512)


class CatchUpdate(BaseModel):
    species: str | None = Field(default=None, min_length=1, max_length=80)
    length_cm: float | None = Field(default=None, gt=0, le=400)
    weight_g: float | None = Field(default=None, gt=0, le=200_000)
    lure_label: str | None = Field(default=None, max_length=120)
    technique: str | None = Field(default=None, max_length=120)
    retrieve_style: str | None = Field(default=None, max_length=120)
    released: bool | None = None
    notes: str | None = Field(default=None, max_length=2000)


class CatchPhotoResponse(ORMModel):
    id: str
    local_uri: str | None = None
    remote_key: str | None = None
    is_primary: bool


class CatchResponse(ORMModel):
    id: str
    session_id: str | None = None
    species_key: str | None = None
    species_label: str
    ai_confidence: float | None = None
    ai_reasoning: str | None = None
    species_confirmed_by_user: bool
    length_cm: float | None = None
    weight_g: float | None = None
    released: bool
    lure_label: str | None = None
    technique: str | None = None
    retrieve_style: str | None = None
    caught_at: datetime
    location_label: str | None = None
    weather_summary: str | None = None
    notes: str | None = None
    photos: list[CatchPhotoResponse] = Field(default_factory=list)


# ---------------------------------------------------------------------------
# Tools (device-side orchestrator calls these)
# ---------------------------------------------------------------------------
class ToolCallRequest(BaseModel):
    """One Gemini functionCall, forwarded by the device for execution."""

    name: str = Field(min_length=1, max_length=64)
    #: Gemini's call id, echoed back so the client can match the response.
    call_id: str | None = Field(default=None, max_length=128)
    args: dict[str, Any] = Field(default_factory=dict)
    language: str | None = Field(default=None, max_length=8)
    live_session_ref: str | None = Field(default=None, max_length=64)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)


class ToolCallBatchRequest(BaseModel):
    """Gemini may emit several functionCalls in one toolCall message."""

    calls: list[ToolCallRequest] = Field(min_length=1, max_length=8)


class ToolCallResult(BaseModel):
    name: str
    call_id: str | None = None
    ok: bool
    response: dict[str, Any]
    error_code: str | None = None


class ToolCallBatchResponse(BaseModel):
    results: list[ToolCallResult]


# ---------------------------------------------------------------------------
# Conditions / misc
# ---------------------------------------------------------------------------
class ConditionsResponse(BaseModel):
    summary: str
    observed_at: datetime
    provider: str
    air_temp_c: float | None = None
    water_temp_c: float | None = None
    wind_speed_kmh: float | None = None
    wind_gust_kmh: float | None = None
    wind_direction: str | None = None
    wind_beaufort: int | None = None
    wave_height_m: float | None = None
    swell_period_s: float | None = None
    cloud_cover_pct: float | None = None
    precipitation_mm: float | None = None
    sunrise: str | None = None
    sunset: str | None = None
    sea_data_available: bool = True


class HomeSummaryResponse(BaseModel):
    """Everything the Home screen needs, in one request."""

    display_name: str
    active_session: SessionResponse | None = None
    last_session: SessionResponse | None = None
    recent_catches: list[CatchResponse] = Field(default_factory=list)
    tackle_count: int
    total_catches: int
    conditions: ConditionsResponse | None = None
    conditions_error: str | None = None


class SpotResponse(ORMModel):
    id: str
    name: str
    latitude: float | None = None
    longitude: float | None = None
    location_precision: str
    water_type: str | None = None
    structure_notes: str | None = None


class SpotCreate(BaseModel):
    name: str = Field(min_length=1, max_length=120)
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    water_type: str | None = Field(default=None, max_length=24)
    structure_notes: str | None = Field(default=None, max_length=2000)


class SpeciesResponse(BaseModel):
    key: str
    name_tr: str
    name_en: str
    scientific_name: str
    habitat: str
    typical_styles: list[str]
    tags: list[str]


class RecommendationRequest(BaseModel):
    target_species: str | None = Field(default=None, max_length=80)
    fishing_style: str | None = None
    latitude: float | None = Field(default=None, ge=-90, le=90)
    longitude: float | None = Field(default=None, ge=-180, le=180)
    notes: str | None = Field(default=None, max_length=500)


# Resolve forward references.
SessionDetailResponse.model_rebuild()
TokenResponse.model_rebuild()
