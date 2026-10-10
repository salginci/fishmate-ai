package com.fishmate.ai.ui.screens.fishing

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fishmate.ai.R
import com.fishmate.ai.live.AssistantPhase
import com.fishmate.ai.ui.components.drawAssistantFace
import com.fishmate.ai.ui.components.rememberReduceMotion
import com.fishmate.ai.ui.theme.GeminiBrand
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** Design-space size the proportions below are expressed in. */
private const val DESIGN = 220f

/**
 * The assistant, drawn as a face that moves with its own voice.
 *
 * This replaces the earlier still orb, and keeps the reason that orb was
 * still: motion here is information, never decoration. The mouth is driven by
 * the playback waveform via [getLevel] rather than by the `SPEAKING` flag, so
 * it articulates syllables instead of hanging open for the whole turn — the
 * difference between something that looks alive and a loading indicator. The
 * rings breathe only while the microphone is actually open, so a glance still
 * answers "is it hearing me?", and everything stops dead when muted.
 *
 * Animated with [withFrameNanos] into float state that is read *inside* the
 * draw lambda: that invalidates drawing only, so sixty frames a second never
 * recompose the screen around it. Mirrors `web/src/ui/Avatar.tsx`.
 */
@Composable
fun AssistantAvatar(
    phase: AssistantPhase,
    isMuted: Boolean,
    getLevel: () -> Float,
    modifier: Modifier = Modifier,
) {
    val level = rememberUpdatedState(getLevel)
    val speaking = phase == AssistantPhase.SPEAKING
    val listening = !isMuted && (
        phase == AssistantPhase.LISTENING ||
            phase == AssistantPhase.THINKING ||
            phase == AssistantPhase.WATCHING ||
            phase == AssistantPhase.TOOL_CALL
        )
    val connecting = phase == AssistantPhase.CONNECTING || phase == AssistantPhase.RECONNECTING
    val brandable = !isMuted && phase != AssistantPhase.ERROR && !connecting

    val accent = when {
        isMuted -> MaterialTheme.colorScheme.outline
        phase == AssistantPhase.ERROR -> MaterialTheme.colorScheme.error
        connecting -> MaterialTheme.colorScheme.secondary
        phase == AssistantPhase.THINKING || phase == AssistantPhase.TOOL_CALL ->
            MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.primary
    }
    val face = when {
        isMuted -> MaterialTheme.colorScheme.surface
        phase == AssistantPhase.ERROR -> MaterialTheme.colorScheme.onError
        connecting || phase == AssistantPhase.THINKING || phase == AssistantPhase.TOOL_CALL ->
            MaterialTheme.colorScheme.onSecondary
        else -> MaterialTheme.colorScheme.onPrimary
    }

    // At zero, nothing idles and only speech moves.
    val reduceMotion = rememberReduceMotion()

    // Written by the frame loop, read in the draw lambda: draw-only invalidation.
    val mouth = remember { mutableFloatStateOf(0f) }
    val breath = remember { mutableFloatStateOf(0f) }
    val blink = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(speaking, listening, reduceMotion) {
        var nextBlinkAt = 0L
        while (true) {
            withFrameNanos { now ->
                val raw = if (speaking) level.value().coerceIn(0f, 1f) else 0f
                // Attack fast, release slow. A symmetric filter makes the mouth
                // flutter on consonants instead of articulating words.
                val smoothed = mouth.floatValue
                mouth.floatValue = smoothed +
                    (raw - smoothed) * (if (raw > smoothed) ATTACK else RELEASE)

                breath.floatValue = if (listening && !reduceMotion) {
                    (sin(now / BREATH_PERIOD_NS) * 0.5f + 0.5f).toFloat()
                } else {
                    0f
                }

                if (nextBlinkAt == 0L) nextBlinkAt = now + BLINK_MIN_NS
                if (!reduceMotion && now > nextBlinkAt) {
                    blink.floatValue = 1f
                    nextBlinkAt = now + BLINK_MIN_NS + Random.nextLong(BLINK_JITTER_NS)
                }
                blink.floatValue = max(0f, blink.floatValue - BLINK_DECAY)
            }
        }
    }

    val description = stringResource(
        when {
            isMuted -> R.string.fishing_avatar_muted
            speaking -> R.string.fishing_avatar_speaking
            else -> R.string.fishing_avatar_listening
        },
    )

    Box(modifier = modifier.size(DESIGN.dp), contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .size(DESIGN.dp)
                .semantics { contentDescription = description },
        ) {
            val open = mouth.floatValue
            val idle = breath.floatValue
            drawAssistantFace(
                accent = accent,
                face = face,
                // Branded only in the ordinary states. Muted, error and
                // connecting keep the semantic colour, because those have to
                // be readable at a glance and a gradient would soften them.
                brandColors = if (brandable) GeminiBrand.stops else null,
                // Energy while speaking, a slow breath while listening, still
                // when muted or broken.
                ringSpread = when {
                    speaking -> open * 26f
                    listening -> idle * 7f
                    else -> 0f
                },
                ringAlpha = if (speaking) 0.3f else if (listening) 0.16f else 0.08f,
                mouthOpen = if (speaking) open else 0f,
                // An occasional blink, so a silent avatar still reads as awake
                // rather than switched off.
                eyeOpen = 1f - blink.floatValue,
                showFace = !connecting,
                struckThrough = isMuted,
            )
        }

        if (connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(30.dp),
                color = face,
                strokeWidth = 3.dp,
            )
        }
    }
}

private const val ATTACK = 0.5f
private const val RELEASE = 0.12f

/** One breath per ~5.7 s, in nanoseconds of frame time. */
private const val BREATH_PERIOD_NS = 900_000_000.0

private const val BLINK_MIN_NS = 2_200_000_000L
private const val BLINK_JITTER_NS = 3_000_000_000L
private const val BLINK_DECAY = 0.12f
