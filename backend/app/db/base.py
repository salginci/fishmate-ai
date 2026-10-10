"""Declarative base and shared column mixins."""

from __future__ import annotations

import uuid
from datetime import UTC, datetime

from sqlalchemy import DateTime, String, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import TypeDecorator


def new_uuid() -> str:
    return str(uuid.uuid4())


def utcnow() -> datetime:
    return datetime.now(UTC)


class UtcDateTime(TypeDecorator):
    """A timestamp that is always timezone-aware UTC in Python.

    Postgres with `timestamptz` returns aware datetimes, but SQLite (used by
    the test suite) returns naive ones, so arithmetic like
    `session.ended_at - session.started_at` raised
    "can't subtract offset-naive and offset-aware datetimes" on SQLite only.

    Normalising in one place means every model, query and service sees the same
    contract on both backends. Naive values arriving from a client are assumed
    to be UTC rather than silently rejected.
    """

    impl = DateTime(timezone=True)
    cache_ok = True

    def process_bind_param(self, value: datetime | None, dialect) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)

    def process_result_value(self, value: datetime | None, dialect) -> datetime | None:
        if value is None:
            return None
        if value.tzinfo is None:
            return value.replace(tzinfo=UTC)
        return value.astimezone(UTC)


class Base(DeclarativeBase):
    """Base for all ORM models."""


class UUIDPrimaryKey:
    """String UUID primary keys.

    Chosen over bigserial so the Android client can generate ids offline and
    sync them later without a server round-trip or id remapping.
    """

    id: Mapped[str] = mapped_column(String(36), primary_key=True, default=new_uuid)


class Timestamps:
    created_at: Mapped[datetime] = mapped_column(
        UtcDateTime, server_default=func.now(), nullable=False
    )
    updated_at: Mapped[datetime] = mapped_column(
        UtcDateTime,
        server_default=func.now(),
        onupdate=func.now(),
        nullable=False,
    )
