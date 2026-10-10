"""Species catalogue and name normalisation.

The model and the user both speak in local names ("çipura", "levrek"); the
database stores a stable `species_key` so history queries work regardless of
which name was used. This catalogue is deliberately small and regional — it
seeds the Mediterranean/Aegean shore species the first users target, and
`normalise()` degrades gracefully to a slugified unknown rather than refusing
to record a catch.

Nothing here encodes regulations. Size limits and closed seasons are a legal
matter and come only from services/regulations.py.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass, field


@dataclass(frozen=True)
class Species:
    key: str
    scientific_name: str
    tr: str
    en: str
    aliases: tuple[str, ...] = ()
    habitat: str = ""
    #: Fishing styles that commonly target this species — used by the
    #: recommendation engine as a prior, not as a rule.
    typical_styles: tuple[str, ...] = ()
    tags: tuple[str, ...] = field(default=())


CATALOGUE: tuple[Species, ...] = (
    Species(
        key="sparus_aurata",
        scientific_name="Sparus aurata",
        tr="Çipura",
        en="Gilthead seabream",
        aliases=("cipura", "çupra", "gilthead", "seabream", "dorada", "orata"),
        habitat=(
            "Sandy and mixed bottoms close to shore, often near structure and brackish outflows."
        ),
        typical_styles=("light_spin", "bottom", "lrf"),
        tags=("bottom_feeder", "shellfish_eater"),
    ),
    Species(
        key="dicentrarchus_labrax",
        scientific_name="Dicentrarchus labrax",
        tr="Levrek",
        en="European seabass",
        aliases=("levrek", "seabass", "sea bass", "bass", "spigola", "robalo"),
        habitat="Surf zone, rocky points, current seams and harbour mouths; hunts in low light.",
        typical_styles=("spin", "light_spin", "surf"),
        tags=("predator", "baitfish_hunter", "low_light"),
    ),
    Species(
        key="sphyraena_sphyraena",
        scientific_name="Sphyraena sphyraena",
        tr="Barakuda",
        en="European barracuda",
        aliases=("barakuda", "barracuda", "iskarmoz"),
        habitat="Mid-water over rocky and mixed ground, often in loose packs near drop-offs.",
        typical_styles=("spin", "light_spin"),
        tags=("predator", "fast_retrieve"),
    ),
    Species(
        key="pomatomus_saltatrix",
        scientific_name="Pomatomus saltatrix",
        tr="Lüfer",
        en="Bluefish",
        aliases=("lufer", "bluefish", "çinekop", "cinekop", "sarıkanat", "sarikanat"),
        habitat="Open water near shore, schooling; aggressive on metal.",
        typical_styles=("spin", "light_spin"),
        tags=("predator", "schooling", "toothy"),
    ),
    Species(
        key="scorpaena_porcus",
        scientific_name="Scorpaena porcus",
        tr="İskorpit",
        en="Black scorpionfish",
        aliases=("iskorpit", "scorpionfish", "skorpit"),
        habitat="Rocky bottom ambush predator, sits tight to structure.",
        typical_styles=("lrf", "bottom"),
        tags=("bottom_feeder", "venomous_spines", "handle_with_care"),
    ),
    Species(
        key="mugil_cephalus",
        scientific_name="Mugil cephalus",
        tr="Kefal",
        en="Flathead grey mullet",
        aliases=("kefal", "mullet", "grey mullet"),
        habitat="Harbours, estuaries and calm shallows; grazes rather than chases.",
        typical_styles=("float", "bottom", "lrf"),
        tags=("grazer", "hard_to_tempt_on_lure"),
    ),
    Species(
        key="argyrosomus_regius",
        scientific_name="Argyrosomus regius",
        tr="Sarıağız",
        en="Meagre",
        aliases=("sariagiz", "sarıağız", "meagre", "corvina"),
        habitat="Surf and deeper sandy channels; large predator, often at night.",
        typical_styles=("surf", "spin"),
        tags=("predator", "large", "night"),
    ),
    Species(
        key="lithognathus_mormyrus",
        scientific_name="Lithognathus mormyrus",
        tr="Mırmır",
        en="Striped seabream",
        aliases=("mirmir", "mırmır", "striped seabream", "sand steenbras"),
        habitat="Clean sandy bottom in shallow water; roots for worms and crustaceans.",
        typical_styles=("bottom", "lrf"),
        tags=("bottom_feeder",),
    ),
    Species(
        key="spicara_smaris",
        scientific_name="Spicara smaris",
        tr="İzmarit",
        en="Picarel",
        aliases=("izmarit", "picarel"),
        habitat="Schooling over mixed ground; common LRF target and live bait.",
        typical_styles=("lrf",),
        tags=("schooling", "small", "baitfish"),
    ),
    Species(
        key="diplodus_sargus",
        scientific_name="Diplodus sargus",
        tr="Sargoz",
        en="White seabream",
        aliases=("sargoz", "white seabream", "sargo"),
        habitat="Rocky shoreline and breakwaters, close in.",
        typical_styles=("bottom", "lrf", "light_spin"),
        tags=("bottom_feeder", "structure"),
    ),
    Species(
        key="seriola_dumerili",
        scientific_name="Seriola dumerili",
        # Was "Akya", which is a different fish. The official circular lists
        # Akya (Lichia amia) at 40 cm and Sarıkuyruk (Seriola dumerili) at
        # 30 cm, so the mix-up would have told an angler holding an undersized
        # akya that it was legal. Regulations are keyed off this catalogue, so
        # a wrong common name here is a fine, not a cosmetic slip.
        tr="Sarıkuyruk",
        en="Greater amberjack",
        aliases=("sarikuyruk", "amberjack", "greater amberjack"),
        habitat="Deep rocky drop-offs and offshore structure; powerful predator.",
        typical_styles=("spin",),
        tags=("predator", "large", "strong"),
    ),
    Species(
        key="lichia_amia",
        scientific_name="Lichia amia",
        tr="Akya",
        en="Leerfish",
        aliases=("akya", "leerfish", "garagoz akya"),
        habitat="Fast open water over sand and shallow reef; chases bait shoals.",
        typical_styles=("spin",),
        tags=("predator", "large", "strong"),
    ),
    Species(
        key="trachurus_trachurus",
        scientific_name="Trachurus trachurus",
        tr="İstavrit",
        en="Atlantic horse mackerel",
        aliases=("istavrit", "horse mackerel", "scad"),
        habitat="Schooling mid-water, very common from piers in low light.",
        typical_styles=("lrf", "light_spin"),
        tags=("schooling", "baitfish", "low_light"),
    ),
)

_BY_KEY = {s.key: s for s in CATALOGUE}


def _fold(text: str) -> str:
    """Lower-case and strip diacritics so 'Çipura', 'cipura', 'ÇİPURA' all match."""
    # Turkish dotless/dotted i needs handling before NFKD or 'İ' folds oddly.
    text = text.replace("İ", "i").replace("I", "ı").lower().replace("ı", "i")
    decomposed = unicodedata.normalize("NFKD", text)
    stripped = "".join(c for c in decomposed if not unicodedata.combining(c))
    return re.sub(r"[^a-z0-9]+", " ", stripped).strip()


_LOOKUP: dict[str, str] = {}
for _s in CATALOGUE:
    for _name in (_s.key, _s.tr, _s.en, _s.scientific_name, *_s.aliases):
        _LOOKUP[_fold(_name)] = _s.key


def normalise(name: str | None) -> str | None:
    """Map a user/model-supplied species name onto a stable key.

    Returns a slug for names we do not know, so an unrecognised species is
    still recorded consistently and can be matched in later history queries.
    """
    if not name or not name.strip():
        return None
    folded = _fold(name)
    if not folded:
        return None
    if folded in _LOOKUP:
        return _LOOKUP[folded]
    # Try single-word containment, e.g. "bir levrek yakaladım" -> levrek.
    for token in folded.split():
        if token in _LOOKUP:
            return _LOOKUP[token]
    for known, key in _LOOKUP.items():
        if known and known in folded:
            return key
    return "unknown_" + folded.replace(" ", "_")[:60]


def get(key: str | None) -> Species | None:
    return _BY_KEY.get(key or "")


def label_for(key: str | None, language: str = "en", fallback: str | None = None) -> str:
    """Display name for a species key in the user's language."""
    species = get(key)
    if species is None:
        if fallback:
            return fallback
        if key and key.startswith("unknown_"):
            return key[len("unknown_") :].replace("_", " ").title()
        return key or "Bilinmeyen"
    return species.tr if language.startswith("tr") else species.en


def describe(key: str | None) -> dict | None:
    species = get(key)
    if species is None:
        return None
    return {
        "key": species.key,
        "scientific_name": species.scientific_name,
        "name_tr": species.tr,
        "name_en": species.en,
        "habitat": species.habitat,
        "typical_styles": list(species.typical_styles),
        "tags": list(species.tags),
    }
