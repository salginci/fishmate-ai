package com.fishmate.ai.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Gemini's brand colours, kept apart from [FishMateTheme]'s own palette.
 *
 * FishMate is not a Google product and does not repaint itself as one. These
 * colours are used in exactly one place — the assistant — because that *is*
 * Gemini, and on the attribution mark that says so. Everything else stays in
 * the deep-water palette, which was chosen for a bright shore and dawn light
 * and has nothing to do with branding.
 *
 * The gradient runs blue → purple → coral, the order the mark uses. The ramp
 * is laid out by whoever draws it, because a sweep sized to the wrong box
 * puts only its middle on the shape and three colours read as one.
 */
object GeminiBrand {
    val Blue = Color(0xFF4285F4)
    val Purple = Color(0xFF9B72CB)
    val Coral = Color(0xFFD96570)

    val stops = listOf(Blue, Purple, Coral)
}
