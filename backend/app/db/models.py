"""Relational schema for FishMate AI.

Design notes
------------
* String UUID PKs so the Android client can create rows offline (see base.py).
* Every user-owned table carries `user_id` and is indexed on it; the API layer
  always filters by the authenticated user — there is no cross-user read path.
* Coordinates live on `FishingSpot` / `FishingSession` and are treated as
  sensitive (docs/PRIVACY.md). `location_precision` records what the user
  consented to share so we never store finer data than they allowed.
* Enum-ish columns are plain strings with a CHECK-free Python enum in front.
  Postgres enums are painful to migrate and this app gains nothing from them.
"""

from __future__ import annotations

import enum
from datetime import datetime

from sqlalchemy import (
    JSON,
    Boolean,
    Float,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.base import Base, Timestamps, UtcDateTime, UUIDPrimaryKey


# ---------------------------------------------------------------------------
# Enumerations (stored as plain strings; `.value` is the persisted form)
# ---------------------------------------------------------------------------
class TackleCategory(enum.StrEnum):
    ROD = "rod"
    REEL = "reel"
    LINE = "line"
    LEADER = "leader"
    LURE = "lure"
    JIGHEAD = "jighead"
    HOOK = "hook"
    SOFT_PLASTIC = "soft_plastic"
    METAL_JIG = "metal_jig"
    MINNOW = "minnow"
    BAIT = "bait"
    ACCESSORY = "accessory"


class FishingStyle(enum.StrEnum):
    LRF = "lrf"
    LIGHT_SPIN = "light_spin"
    SPIN = "spin"
    SURF = "surf"
    BOTTOM = "bottom"
    FLOAT = "float"


class SessionStatus(enum.StrEnum):
    ACTIVE = "active"
    COMPLETED = "completed"
    ABANDONED = "abandoned"


class LocationPrecision(enum.StrEnum):
    PRECISE = "precise"
    APPROXIMATE = "approximate"  # coarsened to ~1 km before storage
    MANUAL = "manual"  # user-named spot, no device GPS
    NONE = "none"


class ObservationKind(enum.StrEnum):
    USER_NOTE = "user_note"
    AI_OBSERVATION = "ai_observation"
    BITE = "bite"
    MISSED_STRIKE = "missed_strike"
    CONDITION_CHANGE = "condition_change"


# ---------------------------------------------------------------------------
# Identity
# ---------------------------------------------------------------------------
class User(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "users"

    email: Mapped[str] = mapped_column(String(320), unique=True, index=True, nullable=False)
    hashed_password: Mapped[str] = mapped_column(String(128), nullable=False)
    display_name: Mapped[str] = mapped_column(String(80), nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    last_seen_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    preferences: Mapped[UserPreferences | None] = relationship(
        back_populates="user", uselist=False, cascade="all, delete-orphan"
    )
    tackle: Mapped[list[TackleItem]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    sessions: Mapped[list[FishingSession]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )
    spots: Mapped[list[FishingSpot]] = relationship(
        back_populates="user", cascade="all, delete-orphan"
    )


class UserPreferences(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "user_preferences"

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), unique=True, index=True, nullable=False
    )
    language: Mapped[str] = mapped_column(String(8), default="en", nullable=False)
    units: Mapped[str] = mapped_column(String(8), default="metric", nullable=False)
    location_precision: Mapped[str] = mapped_column(
        String(16), default=LocationPrecision.APPROXIMATE.value, nullable=False
    )
    default_target_species: Mapped[list | None] = mapped_column(JSON, default=list)
    default_fishing_style: Mapped[str | None] = mapped_column(String(16))
    voice_name: Mapped[str | None] = mapped_column(String(32))
    # Lower = terser spoken answers. Surfaced in Settings as a 3-way choice.
    verbosity: Mapped[str] = mapped_column(String(16), default="concise", nullable=False)
    camera_fps: Mapped[float] = mapped_column(Float, default=0.5, nullable=False)
    share_anonymous_stats: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    user: Mapped[User] = relationship(back_populates="preferences")


# ---------------------------------------------------------------------------
# Tackle
# ---------------------------------------------------------------------------
class TackleItem(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "tackle_items"
    __table_args__ = (Index("ix_tackle_user_category", "user_id", "category"),)

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    category: Mapped[str] = mapped_column(String(24), nullable=False)
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    brand: Mapped[str | None] = mapped_column(String(80))
    model: Mapped[str | None] = mapped_column(String(80))

    # Rods: casting range. Lures/jigheads: actual weight (min == max).
    weight_min_g: Mapped[float | None] = mapped_column(Float)
    weight_max_g: Mapped[float | None] = mapped_column(Float)
    length_cm: Mapped[float | None] = mapped_column(Float)
    # Lines/leaders
    line_rating: Mapped[str | None] = mapped_column(String(32))  # "PE 0.6", "0.26mm FC"
    colour: Mapped[str | None] = mapped_column(String(40))
    fishing_styles: Mapped[list | None] = mapped_column(JSON, default=list)
    target_species: Mapped[list | None] = mapped_column(JSON, default=list)
    notes: Mapped[str | None] = mapped_column(Text)
    is_favourite: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    # Set when the row was created from an AI tackle analysis, so the UI can
    # mark it "AI-suggested, please confirm" and the user can correct it.
    ai_identified: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    user: Mapped[User] = relationship(back_populates="tackle")


# ---------------------------------------------------------------------------
# Spots & sessions
# ---------------------------------------------------------------------------
class FishingSpot(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "fishing_spots"
    __table_args__ = (UniqueConstraint("user_id", "name", name="uq_spot_user_name"),)

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    location_precision: Mapped[str] = mapped_column(
        String(16), default=LocationPrecision.MANUAL.value, nullable=False
    )
    water_type: Mapped[str | None] = mapped_column(String(24))  # sea / lake / river
    structure_notes: Mapped[str | None] = mapped_column(Text)
    is_private: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    user: Mapped[User] = relationship(back_populates="spots")
    sessions: Mapped[list[FishingSession]] = relationship(back_populates="spot")


class FishingSession(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "fishing_sessions"
    __table_args__ = (Index("ix_session_user_started", "user_id", "started_at"),)

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    spot_id: Mapped[str | None] = mapped_column(
        ForeignKey("fishing_spots.id", ondelete="SET NULL"), index=True
    )
    status: Mapped[str] = mapped_column(
        String(16), default=SessionStatus.ACTIVE.value, index=True, nullable=False
    )
    started_at: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    ended_at: Mapped[datetime | None] = mapped_column(UtcDateTime)

    location_label: Mapped[str | None] = mapped_column(String(160))
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    location_precision: Mapped[str] = mapped_column(
        String(16), default=LocationPrecision.NONE.value, nullable=False
    )

    target_species: Mapped[list | None] = mapped_column(JSON, default=list)
    fishing_style: Mapped[str | None] = mapped_column(String(16))
    tackle_used: Mapped[list | None] = mapped_column(JSON, default=list)  # TackleItem ids
    notes: Mapped[str | None] = mapped_column(Text)
    summary: Mapped[str | None] = mapped_column(Text)  # generated at finish
    # Denormalised so history lists and the memory service avoid N+1 counting.
    catch_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    user: Mapped[User] = relationship(back_populates="sessions")
    spot: Mapped[FishingSpot | None] = relationship(back_populates="sessions")
    catches: Mapped[list[Catch]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )
    observations: Mapped[list[SessionObservation]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )
    weather_snapshots: Mapped[list[WeatherSnapshot]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )
    recommendations: Mapped[list[AIRecommendation]] = relationship(
        back_populates="session", cascade="all, delete-orphan"
    )


class SessionObservation(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "session_observations"

    session_id: Mapped[str] = mapped_column(
        ForeignKey("fishing_sessions.id", ondelete="CASCADE"), index=True, nullable=False
    )
    kind: Mapped[str] = mapped_column(String(24), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    observed_at: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    # True when the text came from the model rather than the user, so the UI
    # can label it and the user can delete it.
    from_ai: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    session: Mapped[FishingSession] = relationship(back_populates="observations")


# ---------------------------------------------------------------------------
# Catches
# ---------------------------------------------------------------------------
class Catch(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "catches"
    __table_args__ = (Index("ix_catch_user_species_time", "user_id", "species_key", "caught_at"),)

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    session_id: Mapped[str | None] = mapped_column(
        ForeignKey("fishing_sessions.id", ondelete="SET NULL"), index=True
    )

    # Stable machine key ("dicentrarchus_labrax") + what we show the user.
    species_key: Mapped[str | None] = mapped_column(String(80), index=True)
    species_label: Mapped[str] = mapped_column(String(120), nullable=False)
    # 0..1 when the species came from AI identification; NULL when the user
    # entered it. `species_confirmed_by_user` is the source of truth for trust.
    ai_confidence: Mapped[float | None] = mapped_column(Float)
    ai_reasoning: Mapped[str | None] = mapped_column(Text)
    species_confirmed_by_user: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    length_cm: Mapped[float | None] = mapped_column(Float)
    weight_g: Mapped[float | None] = mapped_column(Float)
    released: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    lure_tackle_id: Mapped[str | None] = mapped_column(
        ForeignKey("tackle_items.id", ondelete="SET NULL")
    )
    rod_tackle_id: Mapped[str | None] = mapped_column(
        ForeignKey("tackle_items.id", ondelete="SET NULL")
    )
    lure_label: Mapped[str | None] = mapped_column(String(120))  # free text fallback
    technique: Mapped[str | None] = mapped_column(String(80))
    retrieve_style: Mapped[str | None] = mapped_column(String(80))

    caught_at: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)
    location_label: Mapped[str | None] = mapped_column(String(160))
    weather_summary: Mapped[str | None] = mapped_column(String(240))
    notes: Mapped[str | None] = mapped_column(Text)

    session: Mapped[FishingSession | None] = relationship(back_populates="catches")
    photos: Mapped[list[CatchPhoto]] = relationship(
        back_populates="catch", cascade="all, delete-orphan"
    )


class CatchPhoto(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "catch_photos"

    catch_id: Mapped[str] = mapped_column(
        ForeignKey("catches.id", ondelete="CASCADE"), index=True, nullable=False
    )
    # Photos stay on the device by default; this is the client-local URI and an
    # optional remote object key if the user opts into backup.
    local_uri: Mapped[str | None] = mapped_column(String(512))
    remote_key: Mapped[str | None] = mapped_column(String(512))
    width: Mapped[int | None] = mapped_column(Integer)
    height: Mapped[int | None] = mapped_column(Integer)
    is_primary: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    catch: Mapped[Catch] = relationship(back_populates="photos")


# ---------------------------------------------------------------------------
# Context & audit
# ---------------------------------------------------------------------------
class WeatherSnapshot(UUIDPrimaryKey, Timestamps, Base):
    __tablename__ = "weather_snapshots"

    session_id: Mapped[str | None] = mapped_column(
        ForeignKey("fishing_sessions.id", ondelete="CASCADE"), index=True
    )
    captured_at: Mapped[datetime] = mapped_column(UtcDateTime, nullable=False)
    provider: Mapped[str] = mapped_column(String(32), nullable=False)
    latitude: Mapped[float | None] = mapped_column(Float)
    longitude: Mapped[float | None] = mapped_column(Float)

    air_temp_c: Mapped[float | None] = mapped_column(Float)
    water_temp_c: Mapped[float | None] = mapped_column(Float)
    wind_speed_kmh: Mapped[float | None] = mapped_column(Float)
    wind_gust_kmh: Mapped[float | None] = mapped_column(Float)
    wind_direction_deg: Mapped[float | None] = mapped_column(Float)
    pressure_hpa: Mapped[float | None] = mapped_column(Float)
    cloud_cover_pct: Mapped[float | None] = mapped_column(Float)
    precipitation_mm: Mapped[float | None] = mapped_column(Float)
    wave_height_m: Mapped[float | None] = mapped_column(Float)
    swell_period_s: Mapped[float | None] = mapped_column(Float)
    summary: Mapped[str | None] = mapped_column(String(240))
    raw: Mapped[dict | None] = mapped_column(JSON)

    session: Mapped[FishingSession | None] = relationship(back_populates="weather_snapshots")


class AIRecommendation(UUIDPrimaryKey, Timestamps, Base):
    """A persisted "what should I use?" answer.

    Kept so the session report can show what was advised, and so we can later
    correlate advice with catches without re-querying the model.
    """

    __tablename__ = "ai_recommendations"

    session_id: Mapped[str | None] = mapped_column(
        ForeignKey("fishing_sessions.id", ondelete="CASCADE"), index=True
    )
    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    created_for: Mapped[str | None] = mapped_column(String(120))  # target species
    rod_tackle_id: Mapped[str | None] = mapped_column(String(36))
    lure_tackle_id: Mapped[str | None] = mapped_column(String(36))
    technique: Mapped[str | None] = mapped_column(String(120))
    retrieve_style: Mapped[str | None] = mapped_column(String(120))
    target_area: Mapped[str | None] = mapped_column(String(160))
    reasoning: Mapped[str | None] = mapped_column(Text)
    confidence: Mapped[float | None] = mapped_column(Float)
    inputs: Mapped[dict | None] = mapped_column(JSON)
    followed_by_catch: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    session: Mapped[FishingSession | None] = relationship(back_populates="recommendations")


class ToolExecution(UUIDPrimaryKey, Timestamps, Base):
    """Audit trail for every Gemini function call the app executed.

    Arguments are stored post-validation; see tools/registry.py. Nothing that
    the redaction rules forbid (tokens, raw media) is ever written here.
    """

    __tablename__ = "tool_executions"
    __table_args__ = (Index("ix_tool_exec_user_time", "user_id", "created_at"),)

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    session_id: Mapped[str | None] = mapped_column(String(36), index=True)
    tool_name: Mapped[str] = mapped_column(String(64), index=True, nullable=False)
    arguments: Mapped[dict | None] = mapped_column(JSON)
    succeeded: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    error_code: Mapped[str | None] = mapped_column(String(64))
    duration_ms: Mapped[int | None] = mapped_column(Integer)
    # Correlates a device-side Live session with server-side execution.
    live_session_ref: Mapped[str | None] = mapped_column(String(64))


class ConversationSummary(UUIDPrimaryKey, Timestamps, Base):
    """Compact memory of a Live conversation.

    The Live API compresses its own context window; this is *our* durable
    memory so a brand-new session can be primed with what matters without
    replaying transcripts (docs/GEMINI_LIVE.md -> "Fishing memory").
    """

    __tablename__ = "conversation_summaries"

    user_id: Mapped[str] = mapped_column(
        ForeignKey("users.id", ondelete="CASCADE"), index=True, nullable=False
    )
    session_id: Mapped[str | None] = mapped_column(
        ForeignKey("fishing_sessions.id", ondelete="CASCADE"), index=True
    )
    summary: Mapped[str] = mapped_column(Text, nullable=False)
    key_facts: Mapped[list | None] = mapped_column(JSON, default=list)
    token_estimate: Mapped[int | None] = mapped_column(Integer)
