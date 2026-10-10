"""Test fixtures.

Tests run against SQLite (aiosqlite) so the suite needs no Postgres and stays
fast in CI. The schema is created from the same metadata the migrations are
generated from, and `tests/test_migrations.py` separately asserts the two agree.

Gemini is never contacted: the Live token service is replaced with a fake
(`FakeLiveTokenService`) the weather provider with `MockWeatherProvider`, and geocoding with
`MockGeocodingProvider` — no test touches Nominatim.
`MockLiveAiSession` on the Android side plays the same role for the client.
"""

from __future__ import annotations

import os
from collections.abc import AsyncGenerator
from datetime import UTC, datetime, timedelta

import pytest

# Configure the environment before anything imports app.config.
os.environ.setdefault("AUTH_SECRET", "t" * 48)
os.environ.setdefault("DATABASE_URL", "sqlite+aiosqlite:///:memory:")
os.environ.setdefault("ENVIRONMENT", "test")
os.environ.setdefault("WEATHER_PROVIDER", "mock")
os.environ.setdefault("GEMINI_API_KEY", "test-key-not-real")
os.environ.setdefault("DEMO_MODE_ENABLED", "true")
os.environ.setdefault("LOG_LEVEL", "WARNING")

from httpx import ASGITransport, AsyncClient  # noqa: E402
from sqlalchemy.ext.asyncio import (  # noqa: E402
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from app.config import get_settings  # noqa: E402
from app.db.base import Base  # noqa: E402
from app.db.session import get_db  # noqa: E402
from app.main import create_app  # noqa: E402
from app.services.geocoding import (  # noqa: E402
    MockGeocodingProvider,
    set_geocoding_provider,
)
from app.services.live_tokens import LiveCredentials, set_live_token_service  # noqa: E402
from app.services.weather import MockWeatherProvider, set_weather_provider  # noqa: E402


def _fake_genai_client(settings):
    """A client instance purely so the SDK converter can run offline.

    No request is made: `build_wire_setup` only uses `client._api_client` to
    resolve the model name and pick the mldev converter.
    """
    from google import genai
    from google.genai import types as gt

    return genai.Client(
        api_key=settings.gemini_api_key or "test-key",
        http_options=gt.HttpOptions(api_version=settings.gemini_api_version),
    )


class FakeLiveTokenService:
    """Stands in for Gemini's token service.

    Records the config it was asked to lock so tests can assert the security
    constraints (tests/test_live_tokens.py) without a network call.
    """

    def __init__(self) -> None:
        self.calls: list[dict] = []

    async def mint(self, *, language="tr", voice_name=None, model=None) -> LiveCredentials:
        from app.services.live_tokens import build_live_config, build_wire_setup

        settings = get_settings()
        config = build_live_config(settings, language=language, voice_name=voice_name)
        self.calls.append({"language": language, "voice_name": voice_name, "config": config})
        now = datetime.now(UTC)
        model_name = model or settings.gemini_live_model
        return LiveCredentials(
            token="fake-ephemeral-token",
            expires_at=now + timedelta(minutes=30),
            new_session_expires_at=now + timedelta(seconds=60),
            model=model_name,
            ws_url="wss://example.invalid/ws?access_token=fake-ephemeral-token",
            # Mirror the real service: the client receives the full wire
            # `setup` body (no `config` wrapper — the server rejects that).
            setup=build_wire_setup(_fake_genai_client(settings), model_name, config),
        )


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


@pytest.fixture
async def engine():
    # A single shared in-memory connection, so every session in a test sees
    # the same database.
    eng = create_async_engine(
        "sqlite+aiosqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=None,
    )
    from sqlalchemy.pool import StaticPool

    await eng.dispose()
    eng = create_async_engine(
        "sqlite+aiosqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    async with eng.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)
    yield eng
    await eng.dispose()


@pytest.fixture
async def session_factory(engine):
    return async_sessionmaker(bind=engine, expire_on_commit=False, class_=AsyncSession)


@pytest.fixture
async def db(session_factory) -> AsyncGenerator[AsyncSession, None]:
    async with session_factory() as session:
        yield session


@pytest.fixture
def fake_live_service() -> FakeLiveTokenService:
    service = FakeLiveTokenService()
    set_live_token_service(service)  # type: ignore[arg-type]
    yield service
    set_live_token_service(None)


@pytest.fixture
async def app(session_factory, fake_live_service):
    set_weather_provider(MockWeatherProvider(get_settings()))
    # No test reaches Nominatim. The mock carries the real ambiguity that
    # motivated the ranking, so it exercises it rather than papering over it.
    set_geocoding_provider(MockGeocodingProvider())
    application = create_app()

    async def override_get_db():
        async with session_factory() as session:
            try:
                yield session
            except Exception:
                await session.rollback()
                raise

    application.dependency_overrides[get_db] = override_get_db
    yield application
    application.dependency_overrides.clear()
    set_weather_provider(None)
    set_geocoding_provider(None)


@pytest.fixture
async def client(app) -> AsyncGenerator[AsyncClient, None]:
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as c:
        yield c


@pytest.fixture
async def registered(client) -> dict:
    """A registered user plus ready-to-use auth headers."""
    response = await client.post(
        "/api/v1/auth/register",
        json={
            "email": "angler@example.com",
            "password": "shore-fishing-123",
            "display_name": "Serkan",
            "language": "tr",
        },
    )
    assert response.status_code == 201, response.text
    body = response.json()
    return {
        "token": body["access_token"],
        "user": body["user"],
        "headers": {"Authorization": f"Bearer {body['access_token']}"},
    }


@pytest.fixture
async def auth_headers(registered) -> dict:
    return registered["headers"]
