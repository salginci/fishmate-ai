"""The "what should I use right now?" decision engine.

This is deliberately *not* a large system prompt. Gear choice depends on the
user's actual inventory, measured wind and wave, time relative to sunset, and
what has produced for them before — all of which live in our data, not in the
model's weights. Encoding it as a scored, auditable function means:

  * the model cannot recommend a lure the user does not own;
  * every recommendation carries the reasons that produced it, so the spoken
    answer is grounded instead of confabulated;
  * the logic is unit-testable (tests/test_recommendation.py).

The model's job is to say the result like a fishing partner would. Scores are
internal; `reasons` is the part meant to be spoken.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.core.observability import get_logger
from app.db.models import TackleCategory, TackleItem
from app.repositories import catches as catch_repo
from app.repositories import tackle as tackle_repo
from app.services import species as species_catalogue
from app.services.weather import ConditionsSnapshot

log = get_logger("recommendation")

#: How close to sunrise/sunset still counts as "low light" — 90 minutes
#: either side, which is roughly the window anglers fish for it.
LOW_LIGHT_WINDOW_SECONDS = 5_400

#: Categories that can be "the thing on the end of the line".
LURE_CATEGORIES = {
    TackleCategory.LURE.value,
    TackleCategory.JIGHEAD.value,
    TackleCategory.SOFT_PLASTIC.value,
    TackleCategory.METAL_JIG.value,
    TackleCategory.MINNOW.value,
}

#: Casting-weight window each style implies, in grams.
STYLE_WEIGHT_RANGE: dict[str, tuple[float, float]] = {
    "lrf": (0.5, 8.0),
    "light_spin": (3.0, 15.0),
    "spin": (7.0, 28.0),
    "surf": (60.0, 200.0),
    "bottom": (10.0, 120.0),
    "float": (1.0, 30.0),
}


@dataclass
class Reason:
    """One factor that moved the recommendation, phrased for speech.

    `kind` lets the client (and the model) keep the prompt's mandated
    distinction: an observation is measured, an inference is deduced.
    """

    kind: str  # "observation" | "inference" | "history"
    text_tr: str
    text_en: str

    def text(self, language: str) -> str:
        return self.text_tr if language.startswith("tr") else self.text_en


@dataclass
class ScoredItem:
    item: TackleItem
    score: float
    reasons: list[Reason] = field(default_factory=list)


@dataclass
class Recommendation:
    target_species: str | None
    target_species_label: str | None
    fishing_style: str | None
    rod: TackleItem | None
    lure: TackleItem | None
    technique_tr: str
    technique_en: str
    retrieve_tr: str
    retrieve_en: str
    target_area_tr: str
    target_area_en: str
    reasons: list[Reason]
    confidence: float
    conditions_summary: str | None
    #: Set when the user owns nothing suitable, so the model asks rather than
    #: inventing gear.
    missing_tackle: list[str] = field(default_factory=list)

    def to_tool_payload(self, language: str = "en") -> dict[str, Any]:
        tr = language.startswith("tr")
        payload: dict[str, Any] = {
            "target_species": self.target_species_label,
            "fishing_style": self.fishing_style,
            "technique": self.technique_tr if tr else self.technique_en,
            "retrieve": self.retrieve_tr if tr else self.retrieve_en,
            "target_area": self.target_area_tr if tr else self.target_area_en,
            "confidence": round(self.confidence, 2),
            "reasons": [{"kind": r.kind, "text": r.text(language)} for r in self.reasons],
        }
        if self.rod is not None:
            payload["rod"] = {
                "id": self.rod.id,
                "name": self.rod.name,
                "casting_range_g": _range_label(self.rod),
            }
        if self.lure is not None:
            payload["lure"] = {
                "id": self.lure.id,
                "name": self.lure.name,
                "category": self.lure.category,
                "weight_g": self.lure.weight_min_g,
            }
        if self.conditions_summary:
            payload["conditions"] = self.conditions_summary
        if self.missing_tackle:
            payload["missing_tackle"] = self.missing_tackle
            payload["instruction_to_model"] = (
                "The user's inventory has no good match for these conditions. Say what "
                "would work and ask what they actually have with them — do NOT "
                "recommend specific gear as if they owned it."
            )
        else:
            payload["instruction_to_model"] = (
                "Deliver this in one or two spoken sentences. Give the lure and the "
                "retrieve first, then at most one reason. Keep the observation / "
                "inference / recommendation distinction."
            )
        return payload


def _range_label(item: TackleItem) -> str | None:
    if item.weight_min_g is None and item.weight_max_g is None:
        return None
    if item.weight_min_g is not None and item.weight_max_g is not None:
        if item.weight_min_g == item.weight_max_g:
            return f"{item.weight_min_g:g} g"
        return f"{item.weight_min_g:g}-{item.weight_max_g:g} g"
    return f"{(item.weight_min_g or item.weight_max_g):g} g"


def _item_weight(item: TackleItem) -> float | None:
    """Representative weight of a lure in grams."""
    if item.weight_min_g is not None and item.weight_max_g is not None:
        return (item.weight_min_g + item.weight_max_g) / 2
    return item.weight_min_g or item.weight_max_g


def choose_style(
    conditions: ConditionsSnapshot | None,
    species_key: str | None,
    requested: str | None,
) -> tuple[str, list[Reason]]:
    """Pick a fishing style from conditions and the target species.

    An explicit user request always wins — the engine advises, it does not
    override what the angler said they're doing.
    """
    reasons: list[Reason] = []
    if requested:
        return requested, reasons

    species = species_catalogue.get(species_key)
    wind = conditions.wind_speed_kmh if conditions else None
    wave = conditions.wave_height_m if conditions else None

    rough = (wave is not None and wave >= 0.8) or (wind is not None and wind >= 30)
    calm = (wave is not None and wave <= 0.3) and (wind is None or wind < 18)

    if rough:
        reasons.append(
            Reason(
                "observation",
                f"Dalga {wave:.1f} m ve rüzgâr {wind:.0f} km/h civarında."
                if wave is not None and wind is not None
                else "Deniz hareketli.",
                f"Waves around {wave:.1f} m with wind near {wind:.0f} km/h."
                if wave is not None and wind is not None
                else "The sea is rough.",
            )
        )
        reasons.append(
            Reason(
                "inference",
                "Bu koşulda hafif takımı kontrol etmek zor; daha ağır sınıf gerekir.",
                "Light gear is hard to control in this, so a heavier class makes sense.",
            )
        )
        style = "spin"
    elif calm:
        reasons.append(
            Reason(
                "observation",
                f"Su sakin, dalga {wave:.1f} m." if wave is not None else "Su sakin.",
                f"Calm water, waves {wave:.1f} m." if wave is not None else "Calm water.",
            )
        )
        style = "light_spin"
    else:
        style = "light_spin"

    if species is not None and species.typical_styles and style not in species.typical_styles:
        preferred = species.typical_styles[0]
        # Keep the heavier class when it is rough: controllability beats
        # the species prior.
        if not rough or preferred in ("spin", "surf"):
            reasons.append(
                Reason(
                    "inference",
                    f"{species.tr} için genelde {preferred.replace('_', ' ')} daha verimli.",
                    f"{species.en} is usually more productive on {preferred.replace('_', ' ')}.",
                )
            )
            style = preferred
    return style, reasons


def _score_rod(item: TackleItem, style: str) -> ScoredItem | None:
    lo, hi = STYLE_WEIGHT_RANGE.get(style, (1.0, 30.0))
    scored = ScoredItem(item=item, score=0.0)
    if item.weight_min_g is None and item.weight_max_g is None:
        scored.score = 0.3  # unknown rating — usable but unproven
        return scored
    rod_lo = item.weight_min_g if item.weight_min_g is not None else 0.0
    rod_hi = item.weight_max_g if item.weight_max_g is not None else rod_lo

    overlap = min(rod_hi, hi) - max(rod_lo, lo)
    if overlap <= 0:
        return None  # genuinely wrong tool for the job
    span = max(hi - lo, 0.1)
    scored.score = 0.5 + 0.5 * min(overlap / span, 1.0)
    if item.fishing_styles and style in item.fishing_styles:
        scored.score += 0.35
    if item.is_favourite:
        scored.score += 0.05
    return scored


def _score_lure(
    item: TackleItem,
    style: str,
    conditions: ConditionsSnapshot | None,
    species_key: str | None,
    history_bonus: dict[str, float],
) -> ScoredItem | None:
    lo, hi = STYLE_WEIGHT_RANGE.get(style, (1.0, 30.0))
    weight = _item_weight(item)
    scored = ScoredItem(item=item, score=0.0)

    if weight is None:
        scored.score = 0.3
    elif not (lo * 0.6 <= weight <= hi * 1.4):
        return None  # outside what this style can cast at all
    else:
        centre = (lo + hi) / 2
        distance = abs(weight - centre) / max(centre, 0.1)
        scored.score = 0.5 + 0.4 * max(0.0, 1.0 - distance)

        # Rougher water and more current favour the heavier end: you need to
        # hold the bottom and keep contact with the lure.
        wave = conditions.wave_height_m if conditions else None
        if wave is not None and wave >= 0.6 and weight >= centre:
            scored.score += 0.2
            scored.reasons.append(
                Reason(
                    "inference",
                    "Dalga var; biraz daha ağır sahteyle teması koruyabilirsin.",
                    "With this chop a slightly heavier lure keeps you in contact.",
                )
            )
        elif wave is not None and wave <= 0.3 and weight <= centre:
            scored.score += 0.15
            scored.reasons.append(
                Reason(
                    "inference",
                    "Su sakin; hafif sahte daha doğal görünür.",
                    "In calm water a lighter lure looks more natural.",
                )
            )

    species = species_catalogue.get(species_key)
    if species is not None:
        if item.target_species and species.key in (item.target_species or []):
            scored.score += 0.25
        if "predator" in species.tags and item.category in (
            TackleCategory.MINNOW.value,
            TackleCategory.METAL_JIG.value,
        ):
            scored.score += 0.2
            scored.reasons.append(
                Reason(
                    "inference",
                    f"{species.tr} baitfish avlıyor; minnow/jig profili uyuyor.",
                    f"{species.en} hunts baitfish, so a minnow or jig profile fits.",
                )
            )
        if "bottom_feeder" in species.tags and item.category in (
            TackleCategory.JIGHEAD.value,
            TackleCategory.SOFT_PLASTIC.value,
        ):
            scored.score += 0.2
            scored.reasons.append(
                Reason(
                    "inference",
                    f"{species.tr} dipte besleniyor; jighead + soft plastic mantıklı.",
                    f"{species.en} feeds on the bottom, so a jighead and soft plastic make sense.",
                )
            )

    # What has actually produced for this user outweighs any theory.
    bonus = history_bonus.get(item.name.strip().lower())
    if bonus:
        scored.score += bonus
        scored.reasons.append(
            Reason(
                "history",
                "Bu sahteyle daha önce balık almışsın.",
                "You have caught fish on this lure before.",
            )
        )

    if item.is_favourite:
        scored.score += 0.05
    return scored


def _light_level(conditions: ConditionsSnapshot | None) -> tuple[str, Reason | None]:
    """Classify light, which drives retrieve speed and depth more than anything.

    Sunrise/sunset arrive as provider-local wall-clock strings, so the
    comparison goes through `sunrise_utc()` / `sunset_utc()`; comparing the raw
    string against `now` was wrong by the location's whole UTC offset and
    reported "close to sunrise" in the middle of the afternoon.
    """
    if conditions is None:
        return "unknown", None
    now = datetime.now(UTC)

    for marker, label_tr, label_en in (
        (conditions.sunset_utc(), "Gün batımına yakın", "Close to sunset"),
        (conditions.sunrise_utc(), "Gün doğumuna yakın", "Close to sunrise"),
    ):
        if marker is not None and abs((now - marker).total_seconds()) <= LOW_LIGHT_WINDOW_SECONDS:
            return "low", Reason(
                "observation",
                f"{label_tr} — ışık düşük.",
                f"{label_en} — the light is low.",
            )
    if conditions.is_day is False:
        return "dark", Reason("observation", "Hava karanlık.", "It is dark.")
    if conditions.cloud_cover_pct is not None and conditions.cloud_cover_pct >= 70:
        return "overcast", Reason(
            "observation",
            f"Bulutluluk %{conditions.cloud_cover_pct:.0f}.",
            f"Cloud cover is {conditions.cloud_cover_pct:.0f}%.",
        )
    return "bright", None


def _technique_for(
    style: str, light: str, species_key: str | None
) -> tuple[str, str, str, str, str, str]:
    """(technique_tr, technique_en, retrieve_tr, retrieve_en, area_tr, area_en)."""
    species = species_catalogue.get(species_key)
    bottom = species is not None and "bottom_feeder" in species.tags
    predator = species is not None and "predator" in species.tags

    if bottom:
        technique = (
            "Dibe yakın çalış, kısa beklemeli",
            "Work close to the bottom with short pauses",
        )
        retrieve = (
            "İki üç tur sar, iki saniye beklet, hafif twitch",
            "Two or three turns, pause two seconds, light twitch",
        )
        area = (
            "Kum-kaya geçişleri ve yapı kenarları",
            "Sand-to-rock transitions and structure edges",
        )
    elif predator and light in ("low", "dark"):
        technique = ("Orta su, düzenli at-çek", "Mid-water, steady cast and retrieve")
        retrieve = (
            "Sabit tempoda sar, arada bir jerk ekle",
            "Steady pace with an occasional jerk",
        )
        area = (
            "Kıyıya paralel, akıntı hattı ve ışık sınırları",
            "Parallel to shore, along current seams and light edges",
        )
    elif predator:
        technique = ("Hızlı at-çek, yüzeye yakın", "Fast cast and retrieve near the surface")
        retrieve = ("Hızlı ve kesintisiz sar", "Fast and continuous")
        area = ("Yapı önü ve derin geçişler", "In front of structure and over drop-offs")
    elif style == "lrf":
        technique = ("Hafif jighead ile dipte arama", "Search the bottom with a light jighead")
        retrieve = (
            "Dibe bırak, kısa sıçramalar, sık beklet",
            "Let it settle, short hops, frequent pauses",
        )
        area = ("Rıhtım ayakları ve kaya dipleri", "Pier legs and rocky bottoms")
    elif style == "surf":
        technique = ("Uzun atış, dipte bekleme", "Long cast, hold on the bottom")
        retrieve = ("Çok yavaş veya sabit bekle", "Very slow, or hold still")
        area = ("Dalga kırılması arkasındaki çukurlar", "Troughs behind the breaking waves")
    else:
        technique = ("Orta su, değişken tempo", "Mid-water with a varied tempo")
        retrieve = (
            "Tempo değiştirerek sar, tepki arayan balığı tetikle",
            "Vary the tempo to trigger a reaction",
        )
        area = ("Yapı kenarları ve akıntı hatları", "Structure edges and current lines")
    return (*technique, *retrieve, *area)


class RecommendationService:
    async def recommend(
        self,
        db: AsyncSession,
        user_id: str,
        *,
        conditions: ConditionsSnapshot | None = None,
        target_species: str | None = None,
        fishing_style: str | None = None,
        notes: str | None = None,
        language: str = "en",
    ) -> Recommendation:
        species_key = species_catalogue.normalise(target_species) if target_species else None
        style, reasons = choose_style(conditions, species_key, fishing_style)

        light, light_reason = _light_level(conditions)
        if light_reason is not None:
            reasons.append(light_reason)

        # Lures that have actually produced, weighted by how often.
        history_bonus: dict[str, float] = {}
        past = await catch_repo.list_recent(db, user_id, limit=40, species_key=species_key)
        for catch in past:
            label = (catch.lure_label or "").strip().lower()
            if label:
                history_bonus[label] = min(history_bonus.get(label, 0.0) + 0.15, 0.45)

        inventory = await tackle_repo.list_for_user(db, user_id)
        rods = [i for i in inventory if i.category == TackleCategory.ROD.value]
        lures = [i for i in inventory if i.category in LURE_CATEGORIES]

        scored_rods = [s for s in (_score_rod(r, style) for r in rods) if s is not None]
        scored_lures = [
            s
            for s in (
                _score_lure(lure, style, conditions, species_key, history_bonus) for lure in lures
            )
            if s is not None
        ]
        scored_rods.sort(key=lambda s: -s.score)
        scored_lures.sort(key=lambda s: -s.score)

        best_rod = scored_rods[0] if scored_rods else None
        best_lure = scored_lures[0] if scored_lures else None

        missing: list[str] = []
        if best_rod is None:
            missing.append(f"a rod suited to {style.replace('_', ' ')}")
        if best_lure is None:
            missing.append(f"a lure in the {style.replace('_', ' ')} weight range")

        if best_lure is not None:
            reasons.extend(best_lure.reasons)

        tech_tr, tech_en, ret_tr, ret_en, area_tr, area_en = _technique_for(
            style, light, species_key
        )

        if notes:
            # Camera/user context the backend cannot measure. Recorded as an
            # observation so the model can cite it, never as a measurement.
            reasons.insert(
                0,
                Reason("observation", f"Senin aktardığın: {notes}", f"You reported: {notes}"),
            )

        confidence = self._confidence(best_rod, best_lure, conditions, bool(past))

        # Keep the spoken answer short: a handful of reasons at most.
        deduped: list[Reason] = []
        seen: set[str] = set()
        for reason in reasons:
            if reason.text_en not in seen:
                seen.add(reason.text_en)
                deduped.append(reason)

        recommendation = Recommendation(
            target_species=species_key,
            target_species_label=(
                species_catalogue.label_for(species_key, language, fallback=target_species)
                if species_key
                else None
            ),
            fishing_style=style,
            rod=best_rod.item if best_rod else None,
            lure=best_lure.item if best_lure else None,
            technique_tr=tech_tr,
            technique_en=tech_en,
            retrieve_tr=ret_tr,
            retrieve_en=ret_en,
            target_area_tr=area_tr,
            target_area_en=area_en,
            reasons=deduped[:4],
            confidence=confidence,
            conditions_summary=conditions.describe(language) if conditions else None,
            missing_tackle=missing,
        )
        log.info(
            "recommendation.built",
            style=style,
            species=species_key,
            has_rod=best_rod is not None,
            has_lure=best_lure is not None,
            confidence=round(confidence, 2),
            reasons=len(deduped),
        )
        return recommendation

    @staticmethod
    def _confidence(
        rod: ScoredItem | None,
        lure: ScoredItem | None,
        conditions: ConditionsSnapshot | None,
        has_history: bool,
    ) -> float:
        """Honest confidence: low when we are guessing, and we say why.

        Starts from the gear match, then is *reduced* for every input we are
        missing, so a recommendation made blind never sounds authoritative.
        """
        if rod is None and lure is None:
            return 0.15
        base = 0.45
        if lure is not None:
            base += min(lure.score, 1.4) * 0.22
        if rod is not None:
            base += min(rod.score, 1.4) * 0.12
        if conditions is None:
            base -= 0.2  # advising with no measured conditions
        elif not conditions.sea_data_available:
            base -= 0.05
        if has_history:
            base += 0.08
        return max(0.1, min(base, 0.95))


_service: RecommendationService | None = None


def get_recommendation_service() -> RecommendationService:
    global _service
    if _service is None:
        _service = RecommendationService()
    return _service
