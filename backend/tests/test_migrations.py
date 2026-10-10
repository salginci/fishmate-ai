"""Migrations must match the models.

Without this, a column added to `app/db/models.py` silently works on a
freshly-created test database and then fails in production where the schema
comes from Alembic.
"""

from __future__ import annotations

from pathlib import Path

from alembic.autogenerate import compare_metadata
from alembic.config import Config
from alembic.migration import MigrationContext
from alembic.script import ScriptDirectory
from sqlalchemy import create_engine

from app.db import models  # noqa: F401
from app.db.base import Base

BACKEND_ROOT = Path(__file__).resolve().parent.parent


def _alembic_config(db_url: str) -> Config:
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(BACKEND_ROOT / "alembic"))
    config.set_main_option("sqlalchemy.url", db_url)
    return config


def test_single_migration_head():
    """Multiple heads mean someone branched and did not merge."""
    script = ScriptDirectory.from_config(_alembic_config("sqlite://"))
    assert len(script.get_heads()) == 1, f"expected one head, got {script.get_heads()}"


def test_migrations_match_models(tmp_path, monkeypatch):
    from alembic import command

    db_path = tmp_path / "drift.db"
    url = f"sqlite:///{db_path}"
    monkeypatch.setenv("DATABASE_URL", f"sqlite+aiosqlite:///{db_path}")

    # get_settings is cached; clear it so the new URL is picked up by env.py.
    from app.config import get_settings

    get_settings.cache_clear()
    try:
        command.upgrade(_alembic_config(url), "head")

        engine = create_engine(url)
        with engine.connect() as connection:
            context = MigrationContext.configure(connection, opts={"compare_type": True})
            diff = compare_metadata(context, Base.metadata)
        engine.dispose()
    finally:
        get_settings.cache_clear()

    # alembic_version is Alembic's own bookkeeping table.
    meaningful = [
        d
        for d in diff
        if not (
            isinstance(d, tuple) and len(d) > 1 and getattr(d[1], "name", "") == "alembic_version"
        )
    ]
    assert not meaningful, (
        "Migrations have drifted from the models. Run:\n"
        "  alembic revision --autogenerate -m 'describe change'\n\n"
        f"Differences: {meaningful}"
    )


def test_migration_is_reversible(tmp_path, monkeypatch):
    from sqlalchemy import inspect

    from alembic import command

    db_path = tmp_path / "revert.db"
    url = f"sqlite:///{db_path}"
    monkeypatch.setenv("DATABASE_URL", f"sqlite+aiosqlite:///{db_path}")
    from app.config import get_settings

    get_settings.cache_clear()
    try:
        config = _alembic_config(url)
        command.upgrade(config, "head")
        engine = create_engine(url)
        assert "users" in inspect(engine).get_table_names()
        engine.dispose()

        command.downgrade(config, "base")
        engine = create_engine(url)
        remaining = set(inspect(engine).get_table_names()) - {"alembic_version"}
        engine.dispose()
    finally:
        get_settings.cache_clear()

    assert not remaining, f"downgrade left tables behind: {remaining}"
