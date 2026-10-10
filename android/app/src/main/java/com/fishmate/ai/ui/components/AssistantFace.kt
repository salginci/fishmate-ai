package com.fishmate.ai.ui.components

import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import kotlin.math.max

/** Design-space size every proportion here is expressed in. */
internal const val AVATAR_DESIGN = 220f

private const val RINGS = 3

/**
 * Draws the assistant's face.
 *
 * Shared by Fishing Mode's live avatar and Home's start button. It lives here
 * rather than in either caller because the alternative was copying 60 lines of
 * canvas work, and two copies of a face drift: the one the angler taps on Home
 * would slowly stop being the one that talks back to them. The *motion* is the
 * caller's business and differs on purpose — a waveform-driven mouth in
 * Fishing Mode, a breath and a ripple on Home — so only the geometry is here.
 *
 * Everything is expressed in a [AVATAR_DESIGN]-unit space and scaled by the
 * smaller dimension, so the same code draws it at 220dp or at 150dp.
 *
 * @param ringSpread how far the concentric rings push out, in design units.
 * @param ringAlpha base opacity of the rings; each successive ring fades.
 * @param mouthOpen 0..1; the mouth keeps a sliver open at 0 so it reads as a
 *   closed mouth rather than a missing one.
 * @param eyeOpen 0..1, where 0 is a completed blink.
 * @param showFace false while connecting, when a spinner takes its place.
 * @param struckThrough draws the unmistakable "it cannot hear you" line.
 * @param brandColors when given, the head and rings are painted with this
 *   gradient instead of [accent]. The assistant wears Gemini's colours
 *   because that is what is speaking; the states that carry meaning — muted,
 *   error, connecting — pass null so a brand colour can never soften
 *   "it cannot hear you".
 *
 *   The gradient is built here, per element, rather than taken ready-made:
 *   sized to the whole canvas it spans a sphere less than half that wide, so
 *   only the middle of the ramp lands on the head and three colours read as
 *   one. Each element gets the full ramp across its own bounds.
 */
internal fun DrawScope.drawAssistantFace(
    accent: Color,
    face: Color,
    ringSpread: Float,
    ringAlpha: Float,
    mouthOpen: Float,
    eyeOpen: Float,
    showFace: Boolean,
    struckThrough: Boolean,
    brandColors: List<Color>? = null,
) {
    val u = size.minDimension / AVATAR_DESIGN
    val centre = Offset(size.width / 2f, size.height / 2f)

    /** The full ramp laid diagonally across a box of this radius. */
    fun ramp(radius: Float): Brush? = brandColors?.let {
        Brush.linearGradient(
            colors = it,
            start = Offset(centre.x - radius, centre.y - radius),
            end = Offset(centre.x + radius, centre.y + radius),
        )
    }

    for (i in RINGS downTo 1) {
        val radius = (44f + i * 13f + ringSpread * (i.toFloat() / RINGS)) * u
        val alpha = ringAlpha * (1f - (i - 1f) / RINGS)
        val brush = ramp(radius)
        if (brush != null) {
            drawCircle(
                brush = brush,
                radius = radius,
                center = centre,
                alpha = alpha,
                style = Stroke(width = 2f * u),
            )
        } else {
            drawCircle(
                color = accent,
                radius = radius,
                center = centre,
                alpha = alpha,
                style = Stroke(width = 2f * u),
            )
        }
    }

    // Head. The brand gradient when one is given, otherwise the accent with
    // an off-centre sheen so it still reads as a sphere.
    val headRadius = 48f * u
    val headBrush = ramp(headRadius)
    if (headBrush != null) {
        drawCircle(brush = headBrush, radius = headRadius, center = centre)
        // A soft highlight over the gradient keeps the sphere, without the
        // second gradient fighting the first.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.White.copy(alpha = 0.22f), Color.Transparent),
                center = centre - Offset(16f * u, 20f * u),
                radius = 40f * u,
            ),
            radius = headRadius,
            center = centre,
        )
    } else {
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.82f), accent),
                center = centre - Offset(14f * u, 18f * u),
                radius = 62f * u,
            ),
            radius = headRadius,
            center = centre,
        )
    }

    if (showFace) {
        for (dx in listOf(-16f, 16f)) {
            val rx = 5f * u
            val ry = (6f * eyeOpen + 0.5f) * u
            drawOval(
                color = face,
                topLeft = centre + Offset(dx * u - rx, -10f * u - ry),
                size = Size(rx * 2f, ry * 2f),
            )
        }

        val openness = max(0.06f, mouthOpen)
        val rx = (30f + openness * 10f) * u / 2f
        val ry = (3f + openness * 24f) * u / 2f
        drawOval(
            color = face,
            topLeft = centre + Offset(-rx, 20f * u - ry),
            size = Size(rx * 2f, ry * 2f),
        )
    }

    if (struckThrough) {
        drawLine(
            color = accent,
            start = centre + Offset(-34f * u, 34f * u),
            end = centre + Offset(34f * u, -34f * u),
            strokeWidth = 4f * u,
        )
    }
}

/**
 * Whether the system has animations switched off.
 *
 * `ANIMATOR_DURATION_SCALE == 0` is the platform's equivalent of
 * `prefers-reduced-motion`, and it is a setting people turn on because motion
 * makes them ill. Both avatars honour it: the face still draws and still
 * answers a tap, but nothing idles.
 */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
}
