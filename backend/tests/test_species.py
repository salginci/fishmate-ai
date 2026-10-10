"""Species normalisation — the backbone of history queries."""

from __future__ import annotations

import pytest

from app.services import species


@pytest.mark.parametrize(
    ("given", "expected"),
    [
        ("çipura", "sparus_aurata"),
        ("Cipura", "sparus_aurata"),
        ("ÇİPURA", "sparus_aurata"),
        ("çupra", "sparus_aurata"),
        ("gilthead", "sparus_aurata"),
        ("Sparus aurata", "sparus_aurata"),
        ("levrek", "dicentrarchus_labrax"),
        ("Levrek ", "dicentrarchus_labrax"),
        ("seabass", "dicentrarchus_labrax"),
        ("sea bass", "dicentrarchus_labrax"),
        ("barakuda", "sphyraena_sphyraena"),
        ("lüfer", "pomatomus_saltatrix"),
        ("çinekop", "pomatomus_saltatrix"),
        ("İskorpit", "scorpaena_porcus"),
        ("akya", "lichia_amia"),
        ("istavrit", "trachurus_trachurus"),
    ],
)
def test_normalises_turkish_and_english(given, expected):
    assert species.normalise(given) == expected


def test_extracts_species_from_a_sentence():
    """Speech transcripts arrive as sentences, not bare species names."""
    assert species.normalise("bir levrek yakaladım") == "dicentrarchus_labrax"
    assert species.normalise("I caught a nice seabass today") == "dicentrarchus_labrax"


@pytest.mark.parametrize("given", ["", "   ", None])
def test_empty_is_none(given):
    assert species.normalise(given) is None


def test_unknown_species_is_recorded_not_dropped():
    """An unknown fish must still be loggable and matchable later."""
    key = species.normalise("zargana")
    assert key == "unknown_zargana"
    # Stable: the same input always yields the same key, so history matches.
    assert species.normalise("Zargana") == key
    assert species.label_for(key) == "Zargana"


def test_labels_follow_language():
    assert species.label_for("dicentrarchus_labrax", "tr") == "Levrek"
    assert species.label_for("dicentrarchus_labrax", "en") == "European seabass"


def test_label_falls_back_to_user_text():
    assert species.label_for(None, "tr", fallback="Garibaldi") == "Garibaldi"


def test_describe_exposes_catalogue_metadata():
    described = species.describe("dicentrarchus_labrax")
    assert described["scientific_name"] == "Dicentrarchus labrax"
    assert "predator" in described["tags"]
    assert described["typical_styles"]


def test_catalogue_keys_are_unique_and_ascii():
    keys = [s.key for s in species.CATALOGUE]
    assert len(keys) == len(set(keys))
    for key in keys:
        assert key.isascii(), f"{key} must be ASCII to be a stable DB key"
