"""Fishing memory — retrieval over the user's own history.

The point of this module is that "Geçen sefer neyle levrek aldım?" is answered
from the database, not from the model's context. Stuffing the whole catch log
into the Live session would be expensive, would blow the context window on a
long session, and would still go stale the moment a new catch was logged.

So the model gets a *tool* that returns a small, already-analysed digest:
counts, the lure that actually produced, and the time-of-day pattern relative
to sunset. The aggregation happens here in Python where it is testable, and the
model's job is only to say it conversationally.
"""

from __future__ import annotations

from collections import Counter
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.observability import get_logger
from app.db.models import Catch, FishingSession
from app.repositories import catches as catch_repo
from app.repositories import sessions as session_repo
from app.services import species as species_catalogue

log = get_logger("memory")

#: Catch counts below this are reported as anecdote, not pattern — three data
#: points is not a trend and the model should not imply it is.
PATTERN_MIN_SAMPLES = 3


@dataclass
class LureRecord:
    label: str
    catches: int = 0
    species: set[str] = field(default_factory=set)
    last_used: datetime | None = None


@dataclass
class CatchDigest:
    """Compact, pre-analysed view of catch history for one query."""

    species_key: str | None
    species_label: str | None
    total: int
    window_days: int | None
    catches: list[dict[str, Any]]
    top_lures: list[dict[str, Any]]
    best_technique: str | None
    time_of_day_pattern: str | None
    average_length_cm: float | None
    largest_length_cm: float | None
    has_enough_data_for_pattern: bool

    def to_tool_payload(self, language: str = "en") -> dict[str, Any]:
        payload: dict[str, Any] = {
            "species": self.species_label,
            "total_matching_catches": self.total,
            "catches": self.catches,
        }
        if self.window_days:
            payload["window_days"] = self.window_days
        if self.top_lures:
            payload["lures_that_produced"] = self.top_lures
        if self.best_technique:
            payload["most_used_technique"] = self.best_technique
        if self.average_length_cm is not None:
            payload["average_length_cm"] = self.average_length_cm
        if self.largest_length_cm is not None:
            payload["largest_length_cm"] = self.largest_length_cm
        if self.time_of_day_pattern:
            payload["time_of_day_pattern"] = self.time_of_day_pattern
        if self.total == 0:
            payload["instruction_to_model"] = (
                "There is NO recorded catch matching this query. Say so plainly. "
                "Do not invent a past catch, lure or session."
            )
        elif not self.has_enough_data_for_pattern:
            payload["instruction_to_model"] = (
                f"Only {self.total} matching record(s) — too few for a pattern. "
                "Refer to them as individual past catches, not as a trend."
            )
        return payload


def _lure_label(catch: Catch) -> str | None:
    return (catch.lure_label or "").strip() or None


def _hour_bucket(when: datetime) -> str:
    hour = when.astimezone(UTC).hour if when.tzinfo else when.hour
    if 5 <= hour < 9:
        return "early_morning"
    if 9 <= hour < 12:
        return "morning"
    if 12 <= hour < 16:
        return "midday"
    if 16 <= hour < 20:
        return "evening"
    return "night"


_BUCKET_TR = {
    "early_morning": "sabah erken",
    "morning": "sabah",
    "midday": "öğle",
    "evening": "akşamüstü",
    "night": "gece",
}


class MemoryService:
    async def catch_digest(
        self,
        db: AsyncSession,
        user_id: str,
        species: str | None = None,
        limit: int = 5,
        days_back: int | None = None,
        language: str = "en",
    ) -> CatchDigest:
        species_key = species_catalogue.normalise(species) if species else None
        limit = max(1, min(limit, 20))

        # Pull a wider slice than `limit` so the aggregates describe the real
        # history, while `catches` returns only what the user asked to hear.
        analysis_pool = await catch_repo.list_recent(
            db, user_id, limit=50, species_key=species_key, days_back=days_back
        )
        shown = analysis_pool[:limit]

        lures: dict[str, LureRecord] = {}
        techniques: Counter[str] = Counter()
        buckets: Counter[str] = Counter()
        lengths: list[float] = []

        for catch in analysis_pool:
            label = _lure_label(catch)
            if label:
                record = lures.setdefault(label.lower(), LureRecord(label=label))
                record.catches += 1
                if catch.species_key:
                    record.species.add(catch.species_key)
                if record.last_used is None or catch.caught_at > record.last_used:
                    record.last_used = catch.caught_at
            if catch.technique:
                techniques[catch.technique.strip()] += 1
            buckets[_hour_bucket(catch.caught_at)] += 1
            if catch.length_cm:
                lengths.append(catch.length_cm)

        top_lures = [
            {
                "lure": r.label,
                "catches": r.catches,
                "last_used": r.last_used.isoformat() if r.last_used else None,
            }
            for r in sorted(lures.values(), key=lambda r: (-r.catches, r.label))[:3]
        ]

        pattern = None
        total = len(analysis_pool)
        if total >= PATTERN_MIN_SAMPLES and buckets:
            bucket, count = buckets.most_common(1)[0]
            if count / total >= 0.5:
                label = (
                    _BUCKET_TR[bucket] if language.startswith("tr") else bucket.replace("_", " ")
                )
                pattern = (
                    f"{count}/{total} kayıt {label} saatlerinde"
                    if language.startswith("tr")
                    else f"{count} of {total} records fall in the {label} window"
                )

        return CatchDigest(
            species_key=species_key,
            species_label=(
                species_catalogue.label_for(species_key, language, fallback=species)
                if species_key
                else None
            ),
            total=total,
            window_days=days_back,
            catches=[self._catch_row(c, language) for c in shown],
            top_lures=top_lures,
            best_technique=techniques.most_common(1)[0][0] if techniques else None,
            time_of_day_pattern=pattern,
            average_length_cm=round(sum(lengths) / len(lengths), 1) if lengths else None,
            largest_length_cm=max(lengths) if lengths else None,
            has_enough_data_for_pattern=total >= PATTERN_MIN_SAMPLES,
        )

    @staticmethod
    def _catch_row(catch: Catch, language: str) -> dict[str, Any]:
        row: dict[str, Any] = {
            "caught_at": catch.caught_at.isoformat(),
            "species": species_catalogue.label_for(
                catch.species_key, language, fallback=catch.species_label
            ),
        }
        for key, value in (
            ("length_cm", catch.length_cm),
            ("weight_g", catch.weight_g),
            ("lure", catch.lure_label),
            ("technique", catch.technique),
            ("location", catch.location_label),
            ("conditions", catch.weather_summary),
            ("notes", catch.notes),
        ):
            if value:
                row[key] = value
        # Make provenance explicit so the model doesn't present an unreviewed
        # AI guess as an established fact about the user's own history.
        if catch.ai_confidence is not None and not catch.species_confirmed_by_user:
            row["species_identified_by"] = "ai"
            row["ai_confidence"] = round(catch.ai_confidence, 2)
        elif catch.species_confirmed_by_user:
            row["species_identified_by"] = "user_confirmed"
        return row

    async def session_digest(
        self,
        db: AsyncSession,
        user_id: str,
        limit: int = 5,
        species: str | None = None,
        language: str = "en",
    ) -> dict[str, Any]:
        limit = max(1, min(limit, 20))
        species_key = species_catalogue.normalise(species) if species else None
        sessions = await session_repo.list_recent(db, user_id, limit=max(limit, 20))

        rows: list[dict[str, Any]] = []
        for session in sessions:
            if species_key:
                targets = [species_catalogue.normalise(t) for t in (session.target_species or [])]
                caught = {c.species_key for c in session.catches}
                if species_key not in targets and species_key not in caught:
                    continue
            rows.append(self._session_row(session, language))
            if len(rows) >= limit:
                break

        productive = [r for r in rows if r.get("catches", 0) > 0]
        payload: dict[str, Any] = {"sessions": rows, "total_returned": len(rows)}
        if not rows:
            payload["instruction_to_model"] = (
                "No matching sessions are recorded. Say so plainly; do not invent one."
            )
        elif productive:
            best = max(productive, key=lambda r: r["catches"])
            payload["most_productive_session"] = {
                "started_at": best.get("started_at"),
                "location": best.get("location"),
                "catches": best.get("catches"),
            }
        return payload

    @staticmethod
    def _session_row(session: FishingSession, language: str) -> dict[str, Any]:
        row: dict[str, Any] = {
            "session_id": session.id,
            "started_at": session.started_at.isoformat(),
            "status": session.status,
            "catches": session.catch_count,
        }
        if session.ended_at:
            minutes = int((session.ended_at - session.started_at).total_seconds() // 60)
            row["duration_minutes"] = max(0, minutes)
        for key, value in (
            ("location", session.location_label),
            ("fishing_style", session.fishing_style),
            ("notes", session.notes),
            ("summary", session.summary),
        ):
            if value:
                row[key] = value
        if session.target_species:
            row["target_species"] = [
                species_catalogue.label_for(species_catalogue.normalise(t), language, fallback=t)
                for t in session.target_species
            ]
        return row

    async def user_profile_brief(
        self, db: AsyncSession, user_id: str, language: str = "en"
    ) -> dict[str, Any]:
        """A few durable facts worth priming a brand-new Live session with.

        Deliberately tiny: this is injected into context, so it must cost a
        handful of tokens, not a transcript.
        """
        recent = await catch_repo.list_recent(db, user_id, limit=30)
        breakdown = await catch_repo.species_breakdown(db, user_id)
        total_sessions = len(await session_repo.list_recent(db, user_id, limit=100))

        lure_counter: Counter[str] = Counter()
        for catch in recent:
            label = _lure_label(catch)
            if label:
                lure_counter[label] += 1

        return {
            "total_sessions": total_sessions,
            "total_catches": await catch_repo.count_for_user(db, user_id),
            "top_species": [
                {
                    "species": species_catalogue.label_for(key, language),
                    "catches": count,
                }
                for key, count in breakdown[:3]
                if key
            ],
            "go_to_lures": [label for label, _ in lure_counter.most_common(3)],
            "last_catch_at": recent[0].caught_at.isoformat() if recent else None,
        }

    async def build_session_report(
        self, db: AsyncSession, session: FishingSession, language: str = "en"
    ) -> str:
        """Human-readable session summary, generated deterministically.

        Not model-generated: a report is shown verbatim in the Sessions screen
        and must be reproducible, cheap and available offline. The model reads
        the headline numbers aloud instead.
        """
        tr = language.startswith("tr")
        ended = session.ended_at or datetime.now(UTC)
        minutes = max(0, int((ended - session.started_at).total_seconds() // 60))
        duration = f"{minutes // 60}h {minutes % 60:02d}m" if minutes >= 60 else f"{minutes}m"

        lures: Counter[str] = Counter()
        for catch in session.catches:
            lure = _lure_label(catch)
            if lure:
                lures[lure] += 1
        winning_lure = lures.most_common(1)[0][0] if lures else None

        weather = None
        if session.weather_snapshots:
            weather = sorted(session.weather_snapshots, key=lambda w: w.captured_at)[0].summary

        # Highlight when activity clustered, which is the thing an angler
        # actually wants from a report.
        activity_note = None
        if session.catches and minutes > 30:
            last_hour_cutoff = ended - timedelta(hours=1)
            in_last_hour = sum(1 for c in session.catches if c.caught_at >= last_hour_cutoff)
            if in_last_hour and in_last_hour >= len(session.catches) / 2:
                activity_note = (
                    "Aktivitenin çoğu son bir saatte yoğunlaştı."
                    if tr
                    else "Most activity happened in the final hour."
                )

        def localised(turkish: str, english: str) -> str:
            return turkish if tr else english

        lines: list[str] = []
        lines.append(localised("OTURUM ÖZETİ", "SESSION SUMMARY"))
        lines.append("")
        if session.location_label:
            lines.append(f"{localised('Yer', 'Location')}: {session.location_label}")
        lines.append(f"{localised('Süre', 'Duration')}: {duration}")
        if session.target_species:
            targets = ", ".join(
                species_catalogue.label_for(species_catalogue.normalise(t), language, fallback=t)
                for t in session.target_species
            )
            lines.append(f"{localised('Hedef', 'Target')}: {targets}")
        if winning_lure:
            lines.append(f"{localised('İşe yarayan', 'What produced')}: {winning_lure}")
        if weather:
            lines.append(f"{localised('Koşullar', 'Conditions')}: {weather}")
        lines.append(f"{localised('Av', 'Catches')}: {len(session.catches)}")
        if session.catches:
            for catch in sorted(session.catches, key=lambda c: c.caught_at):
                name = species_catalogue.label_for(
                    catch.species_key, language, fallback=catch.species_label
                )
                size = f" · {catch.length_cm:.0f} cm" if catch.length_cm else ""
                lure = f" · {catch.lure_label}" if catch.lure_label else ""
                lines.append(f"  - {catch.caught_at.strftime('%H:%M')} {name}{size}{lure}")
        observations = [o for o in session.observations if o.kind in ("bite", "missed_strike")]
        if observations:
            lines.append(f"{localised('Vuruş/kaçan', 'Bites / missed')}: {len(observations)}")
        if activity_note:
            lines.append("")
            lines.append(f"{localised('Gözlem', 'Observation')}: {activity_note}")
        if session.notes:
            lines.append("")
            lines.append(f"{localised('Not', 'Notes')}: {session.notes}")
        return "\n".join(lines)


_service: MemoryService | None = None


def get_memory_service() -> MemoryService:
    global _service
    if _service is None:
        _service = MemoryService()
    return _service
