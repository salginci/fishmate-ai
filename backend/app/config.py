"""Application settings, loaded from environment / .env.

Nothing in this module may carry a default that is a real secret. Secrets are
required at runtime and validated on startup so the process fails loudly
rather than silently running with a broken configuration.
"""

from __future__ import annotations

from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=(".env", "../.env"),
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ---- Gemini -----------------------------------------------------------
    gemini_api_key: str = ""
    gemini_live_model: str = "gemini-3.8-live"
    gemini_text_model: str = "gemini-3.8-flash"
    #: Subtitle translation runs on a lite model: the work is one sentence of
    #: mechanical translation, and the lite tier is cheaper and markedly less
    #: contended — the main flash model was returning 503 "high demand" and
    #: then timing out entirely while lite answered every time. Note that lite
    #: rejects `thinking_config`, so the translation request must not send one.
    gemini_translation_model: str = "gemini-3.5-flash-lite"
    gemini_voice_name: str = "Kore"
    #: How long the angler must be silent before the model treats the sentence
    #: as finished. This is the single biggest lever on perceived latency:
    #: every millisecond here is added to every spoken reply, before the model
    #: has even started thinking. Measured end to end, the model itself takes
    #: about 1.3s to begin answering, so a 900ms window — the original value,
    #: chosen so a windy shoreline would not cut someone off mid-sentence —
    #: made a reply take well over two seconds and felt broken.
    #:
    #: Raise it again if testing outdoors shows the angler being cut off while
    #: pausing to think; that is the real trade-off being made here.
    gemini_vad_silence_ms: int = 550
    #: Audio kept from just before speech is detected, so the first syllable is
    #: not clipped.
    gemini_vad_prefix_padding_ms: int = 300

    # ---- Live API capability toggles -------------------------------------
    #: Google Search grounding as a Live tool.
    #:
    #: OFF by default, and that is a deliberate fail-safe. Search grounding
    #: needs its own quota: a key without it is rejected at *setup* with
    #: `1011 ... You exceeded your current quota`, which kills the whole
    #: session rather than just the search. Verified against the live service
    #: on 2026-10-07 (3/3 attempts). Enable once the key has Search quota.
    gemini_enable_google_search: bool = False

    #: Vision token budget per frame. LOW is deliberate: identifying a lure or a
    #: fish does not need HIGH, and a three-hour session on a phone does.
    gemini_media_resolution: Literal[
        "MEDIA_RESOLUTION_LOW", "MEDIA_RESOLUTION_MEDIUM", "MEDIA_RESOLUTION_HIGH"
    ] = "MEDIA_RESOLUTION_LOW"

    #: Asynchronous (NON_BLOCKING) function calling.
    #: OFF by default: the official tool-use documentation states only that
    #: Gemini 3.1 Flash Live does not support it, and does not confirm support
    #: for gemini-3.8-live. See docs/GEMINI_LIVE_IMPLEMENTATION.md.
    gemini_async_function_calling: bool = False

    #: API version. Affective dialog, proactivity and ephemeral tokens with the
    #: Live API are all documented as v1beta.
    gemini_api_version: str = "v1beta"

    # ---- Ephemeral token policy ------------------------------------------
    ephemeral_token_expire_minutes: int = Field(default=30, ge=1, le=30)
    ephemeral_token_new_session_expire_seconds: int = Field(default=60, ge=10, le=600)
    ephemeral_token_uses: int = Field(default=1, ge=1, le=10)

    # ---- Database ---------------------------------------------------------
    database_url: str = "postgresql+asyncpg://fishmate:fishmate@localhost:5432/fishmate"

    # ---- Auth -------------------------------------------------------------
    auth_secret: str = ""
    auth_algorithm: str = "HS256"
    access_token_expire_minutes: int = 43_200  # 30 days — long sessions offshore

    # ---- Weather ----------------------------------------------------------
    weather_provider: Literal["open_meteo", "mock"] = "open_meteo"
    weather_api_key: str = ""
    weather_timeout_seconds: float = 6.0
    weather_cache_ttl_seconds: int = 600

    # Place names. Nominatim is keyless but asks for an identifying User-Agent
    # and at most one request a second; both are honoured in the provider, and
    # the default agent is overridden per deployment.
    geocoding_provider: Literal["nominatim", "mock", "none"] = "nominatim"
    geocoding_user_agent: str = "FishMateAI/1.0 (https://fisherman.salginci.com)"
    geocoding_timeout_seconds: float = 6.0
    #: A place name does not move, so this is hours rather than minutes.
    geocoding_cache_ttl_seconds: int = 86_400
    geocoding_min_interval_seconds: float = 1.0

    # ---- App --------------------------------------------------------------
    environment: Literal["development", "test", "staging", "production"] = "development"
    log_level: str = "INFO"
    log_json: bool = False
    cors_origins: str = "*"
    demo_mode_enabled: bool = True
    api_prefix: str = "/api/v1"

    # ---- GCP (deployment only) -------------------------------------------
    google_cloud_project: str = ""
    google_cloud_region: str = "europe-west1"

    @field_validator("log_level")
    @classmethod
    def _upper(cls, v: str) -> str:
        return v.upper()

    @property
    def is_production(self) -> bool:
        return self.environment in ("production", "staging")

    @property
    def cors_origin_list(self) -> list[str]:
        raw = self.cors_origins.strip()
        if raw == "*":
            return ["*"]
        return [o.strip() for o in raw.split(",") if o.strip()]

    @property
    def sync_database_url(self) -> str:
        """Alembic runs synchronously; derive a sync URL from the async one."""
        return self.database_url.replace("+asyncpg", "+psycopg2").replace("+aiosqlite", "")

    @property
    def live_api_configured(self) -> bool:
        return bool(self.gemini_api_key)

    def startup_problems(self) -> list[str]:
        """Configuration issues worth refusing to boot over in production."""
        problems: list[str] = []
        if not self.auth_secret:
            problems.append("AUTH_SECRET is not set (generate with: openssl rand -hex 32)")
        elif len(self.auth_secret) < 32:
            problems.append("AUTH_SECRET is shorter than 32 characters")
        if not self.gemini_api_key:
            problems.append("GEMINI_API_KEY is not set — Live token minting will be unavailable")
        if self.is_production and "*" in self.cors_origin_list:
            problems.append("CORS_ORIGINS must be explicit in production, not '*'")
        if self.is_production and self.demo_mode_enabled:
            problems.append("DEMO_MODE_ENABLED should be false in production")
        return problems


@lru_cache
def get_settings() -> Settings:
    return Settings()
