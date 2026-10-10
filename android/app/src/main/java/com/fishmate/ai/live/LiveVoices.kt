package com.fishmate.ai.live

/**
 * The prebuilt Gemini Live voices this app offers.
 *
 * Every one of these was individually verified to open a real `gemini-3.8-live`
 * session — the voice name is part of the locked setup, so an unsupported one
 * does not degrade gracefully, it fails the whole connection at setup and
 * looks to the angler like a broken network. Do not add a name here from the
 * documentation alone; run `make verify-live` against it first.
 *
 * Kept in sync with `VOICES` in `web/src/ui/HomeScreen.tsx`.
 */
val LIVE_VOICES = listOf(
    "Kore",
    "Puck",
    "Charon",
    "Fenrir",
    "Aoede",
    "Leda",
    "Orus",
    "Zephyr",
    "Autonoe",
    "Enceladus",
    "Sulafat",
    "Achernar",
)
