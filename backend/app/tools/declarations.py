"""Gemini function declarations for the fishing tool surface.

These are the *only* tools the model can call. The list is sent to Gemini as
part of the locked ephemeral-token constraints, which means a tampered client
cannot widen the tool surface (docs/SECURITY.md).

Schema dialect is the OpenAPI subset the Gemini API accepts: type names are
upper-case strings and only OBJECT / ARRAY / STRING / NUMBER / INTEGER /
BOOLEAN are available.

Descriptions matter as much as the schema — they are the model's only guidance
on *when* to call a tool, so each one states both purpose and restraint.
"""

from __future__ import annotations

from typing import Any

# Reused fragments -----------------------------------------------------------
_LAT = {
    "type": "NUMBER",
    "description": (
        "Latitude in decimal degrees. Omit to use the session's or user's known location."
    ),
}
_LON = {
    "type": "NUMBER",
    "description": (
        "Longitude in decimal degrees. Omit to use the session's or user's known location."
    ),
}
_SPECIES = {
    "type": "STRING",
    "description": (
        "Species as the user says it, Turkish or English — e.g. 'levrek', "
        "'çipura', 'seabass', 'barracuda'. The backend normalises it."
    ),
}

GET_CURRENT_CONDITIONS = {
    "name": "get_current_conditions",
    "description": (
        "Current observed weather, wind and sea state (wave height, swell period, "
        "water temperature where available) for a shore location. Call this whenever "
        "the answer depends on present conditions — never guess wind or waves. "
        "Returns one combined snapshot so you do not need separate weather and wind calls."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {"latitude": _LAT, "longitude": _LON},
    },
}

GET_CONDITIONS_FORECAST = {
    "name": "get_conditions_forecast",
    "description": (
        "Hourly forecast of wind, waves and precipitation for the next few hours. "
        "Call this only when the user asks about later today or whether to wait — "
        "for 'right now' use get_current_conditions."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "latitude": _LAT,
            "longitude": _LON,
            "hours": {
                "type": "INTEGER",
                "description": "How many hours ahead, 1-24. Defaults to 6.",
            },
        },
    },
}

GET_SUN_TIMES = {
    "name": "get_sun_times",
    "description": (
        "Sunrise, sunset and the dawn/dusk windows for a location and date. "
        "Useful because feeding activity often tracks low light. "
        "Call when timing matters, not for every answer."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "latitude": _LAT,
            "longitude": _LON,
            "date": {
                "type": "STRING",
                "description": "ISO date (YYYY-MM-DD). Defaults to today in the user's timezone.",
            },
        },
    },
}

#: The tackle categories, shared by every declaration that names one so the
#: enum the model sees cannot drift between tools.
_CATEGORY_ENUM = (
    "rod",
    "reel",
    "line",
    "leader",
    "lure",
    "jighead",
    "hook",
    "soft_plastic",
    "metal_jig",
    "minnow",
    "bait",
    "accessory",
)

GET_TACKLE_INVENTORY = {
    "name": "get_tackle_inventory",
    "description": (
        "The user's own rods, reels, lines, leaders and lures. Call this before "
        "recommending any specific item so you only suggest gear they actually own. "
        "Never invent tackle."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "category": {
                "type": "STRING",
                "description": "Optional filter.",
                "enum": list(_CATEGORY_ENUM),
            },
            "fishing_style": {
                "type": "STRING",
                "description": "Optional filter by style.",
                "enum": ["lrf", "light_spin", "spin", "surf", "bottom", "float"],
            },
        },
    },
}

ADD_TACKLE = {
    "name": "add_tackle",
    "description": (
        "Record a rod, reel, line, leader, hook, lure or bait the user has just told you "
        "they own, so the next session already knows it and recommendations can use it. "
        "Call it once per item, only for what they actually said, and only after they said "
        "it — never from a photo you are unsure of, never from your own suggestion, and "
        "never to fill a gap. Items added this way are marked as coming from you, and the "
        "user confirms or corrects them in the app. Say briefly that you noted it."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "category": {
                "type": "STRING",
                "description": "What kind of item it is.",
                "enum": list(_CATEGORY_ENUM),
            },
            "name": {
                "type": "STRING",
                "description": (
                    "What the user called it, in their words, e.g. "
                    "'7 cm batan minnow', 'çin kurdu', 'LRF kamış 1-8 g'."
                ),
            },
            "brand": {"type": "STRING", "description": "Only if they named one."},
            "weight_min_g": {
                "type": "NUMBER",
                "description": "Grams. For a rod, the bottom of its casting range.",
            },
            "weight_max_g": {
                "type": "NUMBER",
                "description": "Grams. For a rod, the top of its casting range.",
            },
            "length_cm": {"type": "NUMBER", "description": "Centimetres, for a lure or rod."},
            "line_rating": {
                "type": "STRING",
                "description": "As they said it, e.g. 'PE 0.6', '0.26 mm FC'.",
            },
            "colour": {"type": "STRING", "description": "Only if they mentioned it."},
            "notes": {
                "type": "STRING",
                "description": "Anything else they said about it, in one short phrase.",
            },
        },
        "required": ["category", "name"],
    },
}

GET_RECENT_CATCHES = {
    "name": "get_recent_catches",
    "description": (
        "The user's logged catches, newest first, with the lure and technique used. "
        "Call this for any question about their own history, e.g. "
        "'what did I catch seabass with last time?'. Never answer such questions from memory."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "species": _SPECIES,
            "limit": {
                "type": "INTEGER",
                "description": "How many catches to return, 1-20. Defaults to 5.",
            },
            "days_back": {
                "type": "INTEGER",
                "description": "Only catches within this many days. Omit for no limit.",
            },
        },
    },
}

GET_RECENT_SESSIONS = {
    "name": "get_recent_sessions",
    "description": (
        "The user's recent fishing sessions with location, conditions, tackle used and "
        "catch counts. Call this for questions about past outings or which spot has "
        "been producing."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "limit": {
                "type": "INTEGER",
                "description": "How many sessions, 1-20. Defaults to 5.",
            },
            "species": _SPECIES,
        },
    },
}

RECOMMEND_SETUP = {
    "name": "recommend_setup",
    "description": (
        "The decision engine for 'what should I use right now?'. It combines the "
        "user's actual tackle, live conditions, time of day and their own catch "
        "history into a ranked setup recommendation with reasoning. "
        "Prefer this over reasoning about gear yourself, because it sees their real "
        "inventory and history. Then deliver its reasoning conversationally."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "target_species": _SPECIES,
            "fishing_style": {
                "type": "STRING",
                "description": "Constrain to a style if the user named one.",
                "enum": ["lrf", "light_spin", "spin", "surf", "bottom", "float"],
            },
            "latitude": _LAT,
            "longitude": _LON,
            "notes": {
                "type": "STRING",
                "description": (
                    "What you can see or what the user told you that the backend "
                    "cannot know — e.g. 'baitfish activity near surface', "
                    "'water very clear', 'strong side current'."
                ),
            },
        },
    },
}

START_FISHING_SESSION = {
    "name": "start_fishing_session",
    "description": (
        "Begin a fishing session, capturing a weather snapshot and the user's target. "
        "Call when the user says they are starting to fish. Confirm briefly out loud "
        "afterwards. Do not call if a session is already active — the result will say so."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "target_species": {
                "type": "ARRAY",
                "items": _SPECIES,
                "description": "One or more species the user is targeting.",
            },
            "location_label": {
                "type": "STRING",
                "description": "Spot name as the user calls it, e.g. 'Bostanlı'.",
            },
            "fishing_style": {
                "type": "STRING",
                "enum": ["lrf", "light_spin", "spin", "surf", "bottom", "float"],
            },
            "latitude": _LAT,
            "longitude": _LON,
        },
    },
}

FINISH_FISHING_SESSION = {
    "name": "finish_fishing_session",
    "description": (
        "End the active session and produce its summary report. Call when the user "
        "says they are done. Read back the headline numbers, not the whole report."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "notes": {
                "type": "STRING",
                "description": "Any closing remark the user made about the session.",
            }
        },
    },
}

LOG_CATCH = {
    "name": "log_catch",
    "description": (
        "Record a catch. Call after the user confirms they want it logged. "
        "If species came from your own camera identification, pass your confidence "
        "and the visual cues in ai_reasoning so the user can correct it later. "
        "Ask for length before calling if the user has not said it, but do not block "
        "on weight."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "species": _SPECIES,
            "ai_confidence": {
                "type": "NUMBER",
                "description": (
                    "Your confidence 0.0-1.0 if you identified it visually. "
                    "Omit when the user stated the species themselves."
                ),
            },
            "ai_reasoning": {
                "type": "STRING",
                "description": "The visual cues behind your identification, one short sentence.",
            },
            "length_cm": {"type": "NUMBER", "description": "Length in centimetres."},
            "weight_g": {"type": "NUMBER", "description": "Weight in grams."},
            "lure_label": {
                "type": "STRING",
                "description": "Lure used, e.g. '11g minnow' or a tackle item name.",
            },
            "technique": {
                "type": "STRING",
                "description": "Technique used, e.g. 'light spin, slow retrieve near bottom'.",
            },
            "released": {
                "type": "BOOLEAN",
                "description": "True if released. Defaults to true.",
            },
            "notes": {"type": "STRING", "description": "Anything else the user mentioned."},
        },
        "required": ["species"],
    },
}

ADD_SESSION_OBSERVATION = {
    "name": "add_session_observation",
    "description": (
        "Append a timestamped note to the active session — a bite, a missed strike, "
        "a change in conditions, or something you observed through the camera. "
        "Call this when the user reports an event ('something just hit') so the "
        "session report can show when activity happened. Keep the text factual."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "kind": {
                "type": "STRING",
                "enum": [
                    "user_note",
                    "ai_observation",
                    "bite",
                    "missed_strike",
                    "condition_change",
                ],
            },
            "text": {"type": "STRING", "description": "One or two factual sentences."},
        },
        "required": ["kind", "text"],
    },
}

GET_FISHING_REGULATIONS = {
    "name": "get_fishing_regulations",
    "description": (
        "Look up fishing regulations — minimum sizes, closed seasons, protected "
        "species — from a configured authoritative source. You MUST call this instead "
        "of answering any regulatory question yourself. If it returns unverified or "
        "no data, tell the user plainly that you cannot confirm it and name the "
        "official authority. Never state a size limit or closed season you did not "
        "get from this tool."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "species": _SPECIES,
            "region": {
                "type": "STRING",
                "description": "Country or region code, e.g. 'TR'. Defaults to the user's region.",
            },
        },
    },
}

FIND_PLACE = {
    "name": "find_place",
    "description": (
        "Turn a place name the user said — 'Bostanlı', 'Kilyos', 'Çeşme' — into "
        "coordinates. You MUST call this whenever the user asks about anywhere other "
        "than where they are, then pass the returned latitude and longitude to "
        "get_current_conditions, get_conditions_forecast or get_sun_times. Never tell "
        "the user you cannot check a named spot: this is how you check it. "
        "If it returns several options, read out the regions and ask which one they "
        "mean — same-named villages are often hundreds of kilometres inland, and "
        "answering for the wrong one would be worse than asking."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "name": {
                "type": "STRING",
                "description": (
                    "The place as the user said it. Add the city if they gave one "
                    "('Bostanlı, İzmir'); it makes the match far more reliable."
                ),
            },
        },
        "required": ["name"],
    },
}

SET_CAMERA = {
    "name": "set_camera",
    "description": (
        "Turn the angler's camera on or off. The camera starts OFF in every session, "
        "so you cannot see anything until you call this with enabled=true. "
        "Call it the moment the user refers to something visual — 'do you see this?', "
        "'bunu goruyor musun?', 'look at this', 'what fish is this?', 'is this lure "
        "right?' — instead of telling them to press a button. After it returns "
        "successfully a camera frame has already been sent to you, so describe what "
        "you actually see. Call it with enabled=false once you are done looking: "
        "video shortens the session and costs the angler bandwidth and battery. "
        "Do not call it for questions that need no sight, and do not call it twice "
        "in a row for the same state."
    ),
    "parameters": {
        "type": "OBJECT",
        "properties": {
            "enabled": {
                "type": "BOOLEAN",
                "description": "True to start seeing, false to stop.",
            },
        },
        "required": ["enabled"],
    },
}

#: Declarations exposed to the Live session, in a stable order.
FUNCTION_DECLARATIONS: list[dict[str, Any]] = [
    GET_CURRENT_CONDITIONS,
    GET_CONDITIONS_FORECAST,
    GET_SUN_TIMES,
    GET_TACKLE_INVENTORY,
    ADD_TACKLE,
    GET_RECENT_CATCHES,
    GET_RECENT_SESSIONS,
    RECOMMEND_SETUP,
    START_FISHING_SESSION,
    FINISH_FISHING_SESSION,
    LOG_CATCH,
    ADD_SESSION_OBSERVATION,
    GET_FISHING_REGULATIONS,
    FIND_PLACE,
    SET_CAMERA,
]

TOOL_NAMES: tuple[str, ...] = tuple(d["name"] for d in FUNCTION_DECLARATIONS)

#: Tools the **device** executes, not the backend.
#:
#: These are declared here because the declaration is what the model sees, and
#: that has to be minted with the session token. But they act on hardware the
#: server cannot reach, so the client intercepts them before it ever posts a
#: batch. A name in this set arriving at `/tools/execute-batch` means a client
#: forgot to intercept it, which is why the registry answers it with an
#: explicit message instead of "unknown tool".
DEVICE_TOOLS: frozenset[str] = frozenset({"set_camera"})


#: Tools slow enough that blocking the conversation on them is noticeable.
#:
#: `get_fishing_regulations` can take seconds when it falls through to a
#: Google-Search-grounded lookup, which is exactly the case asynchronous
#: function calling exists for. Everything else here answers in milliseconds
#: (weather ~300 ms, database 1-10 ms), where NON_BLOCKING would add
#: complexity for no perceptible gain.
SLOW_TOOLS: frozenset[str] = frozenset({"get_fishing_regulations"})


def live_tools_payload(
    include_google_search: bool = True,
    non_blocking: bool = False,
) -> list[dict[str, Any]]:
    """The `tools` array for BidiGenerateContentSetup.

    Google Search is included as the API's *built-in* tool (`google_search`),
    not as a custom HTTP search that injects text, so grounding happens inside
    the Live session and arrives with `groundingMetadata`.

    @param non_blocking
        When true, tools in [SLOW_TOOLS] are declared with
        `behavior: "NON_BLOCKING"` so the model keeps talking while they run.
        Default false: the official tool-use documentation confirms only that
        Gemini 3.1 Flash Live does *not* support asynchronous function calling
        and does not state support for `gemini-3.8-live`. Enabling an
        unconfirmed capability on a demo model is the wrong trade, so this is a
        documented switch rather than a default.
    """
    declarations: list[dict[str, Any]] = []
    for declaration in FUNCTION_DECLARATIONS:
        if non_blocking and declaration["name"] in SLOW_TOOLS:
            declarations.append({**declaration, "behavior": "NON_BLOCKING"})
        else:
            declarations.append(declaration)

    tools: list[dict[str, Any]] = [{"function_declarations": declarations}]
    if include_google_search:
        tools.append({"google_search": {}})
    return tools
