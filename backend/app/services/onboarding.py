"""Starter tackle templates offered during onboarding.

The product must not assume what the user owns: a recommendation engine that
suggests a rod the angler does not have is worse than useless. So nothing is
seeded at registration. Instead onboarding shows these common shore-fishing
classes and the user taps the ones they actually have, editing weights to match
their own gear.

They are templates, not a fixed inventory — `TEMPLATES` is data, and a user in
another region can ignore all of it and add their own items.
"""

from __future__ import annotations

from typing import Any

from sqlalchemy.ext.asyncio import AsyncSession

from app.db.models import TackleCategory
from app.repositories import tackle as tackle_repo

#: Common Turkish/Mediterranean shore-fishing classes, grouped by style so
#: onboarding can present "I do LRF" rather than 20 individual checkboxes.
TEMPLATES: dict[str, dict[str, Any]] = {
    "lrf": {
        "label_tr": "LRF (ultra hafif)",
        "label_en": "LRF (ultra light)",
        "items": [
            {
                "category": TackleCategory.ROD.value,
                "name": "LRF kamış 1-8 g",
                "weight_min_g": 1,
                "weight_max_g": 8,
                "fishing_styles": ["lrf"],
            },
            {
                "category": TackleCategory.JIGHEAD.value,
                "name": "Jighead 2 g",
                "weight_min_g": 2,
                "weight_max_g": 2,
                "fishing_styles": ["lrf"],
            },
            {
                "category": TackleCategory.JIGHEAD.value,
                "name": "Jighead 3 g",
                "weight_min_g": 3,
                "weight_max_g": 3,
                "fishing_styles": ["lrf"],
            },
            {
                "category": TackleCategory.SOFT_PLASTIC.value,
                "name": "Soft plastic 5 cm",
                "weight_min_g": 1,
                "weight_max_g": 2,
                "length_cm": 5,
                "fishing_styles": ["lrf", "light_spin"],
            },
            {
                "category": TackleCategory.LINE.value,
                "name": "PE 0.4",
                "line_rating": "PE 0.4",
                "fishing_styles": ["lrf"],
            },
            {
                "category": TackleCategory.LEADER.value,
                "name": "Fluorocarbon 0.16 mm",
                "line_rating": "0.16 mm FC",
                "fishing_styles": ["lrf"],
            },
        ],
    },
    "light_spin": {
        "label_tr": "Light spin (3-15 g)",
        "label_en": "Light spin (3-15 g)",
        "items": [
            {
                "category": TackleCategory.ROD.value,
                "name": "Light spin kamış 3-15 g",
                "weight_min_g": 3,
                "weight_max_g": 15,
                "fishing_styles": ["light_spin"],
            },
            {
                "category": TackleCategory.JIGHEAD.value,
                "name": "Jighead 7 g",
                "weight_min_g": 7,
                "weight_max_g": 7,
                "fishing_styles": ["light_spin"],
            },
            {
                "category": TackleCategory.MINNOW.value,
                "name": "Minnow 7 cm / 7 g",
                "weight_min_g": 7,
                "weight_max_g": 7,
                "length_cm": 7,
                "fishing_styles": ["light_spin"],
            },
            {
                "category": TackleCategory.SOFT_PLASTIC.value,
                "name": "Soft plastic 7 cm",
                "weight_min_g": 3,
                "weight_max_g": 5,
                "length_cm": 7,
                "fishing_styles": ["light_spin"],
            },
            {
                "category": TackleCategory.LINE.value,
                "name": "PE 0.6",
                "line_rating": "PE 0.6",
                "fishing_styles": ["light_spin"],
            },
            {
                "category": TackleCategory.LEADER.value,
                "name": "Fluorocarbon 0.22 mm",
                "line_rating": "0.22 mm FC",
                "fishing_styles": ["light_spin"],
            },
        ],
    },
    "spin": {
        "label_tr": "Spin (7-28 g)",
        "label_en": "Spin (7-28 g)",
        "items": [
            {
                "category": TackleCategory.ROD.value,
                "name": "Spin kamış 7-28 g",
                "weight_min_g": 7,
                "weight_max_g": 28,
                "fishing_styles": ["spin"],
            },
            {
                "category": TackleCategory.MINNOW.value,
                "name": "Minnow 11 cm / 11 g",
                "weight_min_g": 11,
                "weight_max_g": 11,
                "length_cm": 11,
                "fishing_styles": ["spin"],
                "target_species": ["dicentrarchus_labrax"],
            },
            {
                "category": TackleCategory.METAL_JIG.value,
                "name": "Metal jig 20 g",
                "weight_min_g": 20,
                "weight_max_g": 20,
                "fishing_styles": ["spin"],
            },
            {
                "category": TackleCategory.LINE.value,
                "name": "PE 1.0",
                "line_rating": "PE 1.0",
                "fishing_styles": ["spin"],
            },
            {
                "category": TackleCategory.LEADER.value,
                "name": "Fluorocarbon 0.30 mm",
                "line_rating": "0.30 mm FC",
                "fishing_styles": ["spin"],
            },
        ],
    },
    "surf": {
        "label_tr": "Surf (100-200 g)",
        "label_en": "Surf (100-200 g)",
        "items": [
            {
                "category": TackleCategory.ROD.value,
                "name": "Surf kamış 100-200 g",
                "weight_min_g": 100,
                "weight_max_g": 200,
                "fishing_styles": ["surf"],
            },
            {
                "category": TackleCategory.HOOK.value,
                "name": "Surf iğne 1/0",
                "fishing_styles": ["surf", "bottom"],
            },
            {
                "category": TackleCategory.LINE.value,
                "name": "Mono 0.35 mm",
                "line_rating": "0.35 mm mono",
                "fishing_styles": ["surf"],
            },
        ],
    },
}


def list_templates(language: str = "en") -> list[dict[str, Any]]:
    tr = language.startswith("tr")
    return [
        {
            "key": key,
            "label": tpl["label_tr"] if tr else tpl["label_en"],
            "item_count": len(tpl["items"]),
            "items": [
                {
                    "category": item["category"],
                    "name": item["name"],
                    "weight_min_g": item.get("weight_min_g"),
                    "weight_max_g": item.get("weight_max_g"),
                    "line_rating": item.get("line_rating"),
                }
                for item in tpl["items"]
            ],
        }
        for key, tpl in TEMPLATES.items()
    ]


async def apply_templates(db: AsyncSession, user_id: str, keys: list[str]) -> list[Any]:
    """Add the chosen templates' items to the user's inventory.

    Skips names the user already has so re-running onboarding, or picking two
    overlapping templates, does not create duplicates.
    """
    existing = {
        item.name.strip().lower()
        for item in await tackle_repo.list_for_user(db, user_id, include_inactive=True)
    }
    created = []
    for key in keys:
        template = TEMPLATES.get(key)
        if template is None:
            continue
        for fields in template["items"]:
            if fields["name"].strip().lower() in existing:
                continue
            existing.add(fields["name"].strip().lower())
            created.append(await tackle_repo.create(db, user_id, **dict(fields)))
    return created
