"""Structured logging, request IDs and lightweight metrics.

Logging rules enforced here (docs/SECURITY.md):
  * never log API keys, ephemeral tokens, JWTs or passwords
  * never log raw audio or image bytes
  * coordinates are rounded before they reach a log line
"""

from __future__ import annotations

import logging
import re
import sys
import time
import uuid
from collections import defaultdict
from collections.abc import MutableMapping
from contextvars import ContextVar
from typing import Any

import structlog

request_id_ctx: ContextVar[str] = ContextVar("request_id", default="-")
user_id_ctx: ContextVar[str] = ContextVar("user_id", default="-")

# Values matching these key names are replaced wholesale.
_SENSITIVE_KEYS = re.compile(
    r"(api[_-]?key|token|secret|password|authorization|access_token|credential)",
    re.IGNORECASE,
)
_REDACTED = "«redacted»"


def _redact(
    _logger: Any, _name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    for key in list(event_dict.keys()):
        if _SENSITIVE_KEYS.search(key):
            event_dict[key] = _REDACTED
    return event_dict


def _add_context(
    _logger: Any, _name: str, event_dict: MutableMapping[str, Any]
) -> MutableMapping[str, Any]:
    event_dict["request_id"] = request_id_ctx.get()
    uid = user_id_ctx.get()
    if uid != "-":
        event_dict["user_id"] = uid
    return event_dict


def configure_logging(level: str = "INFO", json_output: bool = False) -> None:
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=getattr(logging, level, logging.INFO),
    )
    # uvicorn's access log duplicates our request middleware
    logging.getLogger("uvicorn.access").disabled = True

    renderer = (
        structlog.processors.JSONRenderer()
        if json_output
        else structlog.dev.ConsoleRenderer(colors=sys.stdout.isatty())
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            _add_context,
            _redact,
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            renderer,
        ],
        wrapper_class=structlog.make_filtering_bound_logger(getattr(logging, level, logging.INFO)),
        logger_factory=structlog.PrintLoggerFactory(),
        cache_logger_on_first_use=True,
    )


def get_logger(name: str = "fishmate") -> Any:
    return structlog.get_logger(name)


def new_request_id() -> str:
    return uuid.uuid4().hex[:16]


def round_coords(lat: float | None, lon: float | None) -> str:
    """Coarsen coordinates for logs — ~1 km, never a precise fishing spot."""
    if lat is None or lon is None:
        return "-"
    return f"{lat:.2f},{lon:.2f}"


class Metrics:
    """In-process counters/timers.

    Deliberately simple: a single Cloud Run instance exports these at
    /api/v1/health/metrics and the surrounding platform scrapes them. Swap for
    OpenTelemetry by reimplementing this class — nothing else depends on it.
    """

    def __init__(self) -> None:
        self._counters: dict[str, int] = defaultdict(int)
        self._durations: dict[str, list[float]] = defaultdict(list)

    def incr(self, name: str, value: int = 1, **labels: str) -> None:
        self._counters[self._key(name, labels)] += value

    def observe(self, name: str, seconds: float, **labels: str) -> None:
        bucket = self._durations[self._key(name, labels)]
        bucket.append(seconds)
        if len(bucket) > 1000:  # bounded memory
            del bucket[:-1000]

    @staticmethod
    def _key(name: str, labels: dict[str, str]) -> str:
        if not labels:
            return name
        tags = ",".join(f"{k}={v}" for k, v in sorted(labels.items()))
        return f"{name}{{{tags}}}"

    def snapshot(self) -> dict[str, Any]:
        timers = {}
        for key, values in self._durations.items():
            if not values:
                continue
            ordered = sorted(values)
            timers[key] = {
                "count": len(ordered),
                "p50_ms": round(ordered[len(ordered) // 2] * 1000, 2),
                "p95_ms": round(ordered[min(len(ordered) - 1, int(len(ordered) * 0.95))] * 1000, 2),
                "max_ms": round(ordered[-1] * 1000, 2),
            }
        return {"counters": dict(self._counters), "timers": timers}


metrics = Metrics()


class Timer:
    """`with Timer("name", tool="x"):` records a duration observation."""

    def __init__(self, name: str, **labels: str) -> None:
        self.name = name
        self.labels = labels
        self._start = 0.0

    def __enter__(self) -> Timer:
        self._start = time.perf_counter()
        return self

    def __exit__(self, *_exc: object) -> None:
        metrics.observe(self.name, time.perf_counter() - self._start, **self.labels)
