package com.fishmate.ai.ui.screens.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fishmate.ai.R
import com.fishmate.ai.ui.components.AVATAR_DESIGN
import com.fishmate.ai.ui.components.drawAssistantFace
import com.fishmate.ai.ui.components.rememberReduceMotion
import com.fishmate.ai.ui.theme.GeminiBrand
import kotlin.random.Random
import kotlinx.coroutines.delay

/** How big the face is drawn on Home. Fishing Mode draws it larger. */
private val AVATAR_SIZE = 168.dp

/**
 * The assistant, as Home's one dominant action.
 *
 * This replaces a rectangular "Start fishing" button, and the reason is not
 * decoration: the product *is* a thing you talk to, so the thing you tap to
 * start talking should be its face. It draws the same geometry as Fishing
 * Mode's avatar ([drawAssistantFace]), so tapping it leads somewhere that
 * looks like where you tapped.
 *
 * The motion carries the information the old button's icon and label carried,
 * and nothing more:
 *
 * - **Breathing** — a slow, small ring pulse. It says the assistant is there
 *   and waiting, which is what makes a face worth tapping rather than a shape.
 * - **A wave travelling outward** — only while a session is already running.
 *   That is the state the old button spelled out by swapping its icon, and it
 *   has to survive a glance from a moving boat.
 * - **A press that shrinks it** — touch confirmation on a target with no edges
 *   to highlight.
 *
 * The label stays underneath: a bare face is a guessing game for anyone who
 * has not used the app before. It reads the same in both states, because
 * "resume session" described the mechanism rather than the activity, and
 * whether one is already running is said by the wave and by the session card
 * immediately below.
 *
 * Idle motion is an infinite transition rather than Fishing Mode's
 * `withFrameNanos` loop. There the loop earns its keep on a waveform that
 * genuinely changes every frame; here it would spend a frame's budget every
 * 16ms on a screen that is mostly being scrolled. Blinking is a timer instead
 * of part of the transition because the interval has to be irregular — an
 * exactly periodic blink reads as a machine.
 */
@Composable
fun StartFishingAvatar(
    activeSession: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val reduceMotion = rememberReduceMotion()
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()

    // The same words whether or not a session is running. "Resume session"
    // named the mechanism rather than the activity, and the running state is
    // already said twice below this: by the wave, and by the session card.
    val label = stringResource(R.string.home_lets_fish)

    // Every animated value here is read inside a graphicsLayer or draw lambda,
    // so it animates without recomposing Home's list around it.
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(durationMillis = if (reduceMotion) 0 else 110),
        label = "avatarPress",
    )

    val transition = rememberInfiniteTransition(label = "avatarIdle")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = BREATH_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = WAVE_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave",
    )

    val blink = remember { Animatable(1f) }
    LaunchedEffect(reduceMotion) {
        if (reduceMotion) return@LaunchedEffect
        while (true) {
            delay(BLINK_MIN_MS + Random.nextLong(BLINK_JITTER_MS))
            blink.animateTo(0f, tween(90, easing = LinearEasing))
            blink.animateTo(1f, tween(130, easing = LinearEasing))
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val face = MaterialTheme.colorScheme.onPrimary

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Canvas(
            modifier = Modifier
                .size(AVATAR_SIZE)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                // Clipped to a circle so the platform's press ripple stays
                // inside the head instead of flashing a square around it.
                .clip(CircleShape)
                .clickable(
                    interactionSource = interactions,
                    indication = ripple(),
                    role = Role.Button,
                    onClick = onClick,
                )
                // The visible label is a sibling, so without this the button
                // would be announced with no name at all.
                .semantics { contentDescription = label },
        ) {
            val idle = if (reduceMotion) 0f else breath

            if (activeSession && !reduceMotion) {
                // Travels from the head outward and fades, so "still running"
                // is legible without reading anything.
                val u = size.minDimension / AVATAR_DESIGN
                drawCircle(
                    color = accent,
                    radius = (52f + wave * 50f) * u,
                    center = Offset(size.width / 2f, size.height / 2f),
                    alpha = 0.35f * (1f - wave),
                    style = Stroke(width = 2.5f * u),
                )
            }

            drawAssistantFace(
                accent = accent,
                face = face,
                brandColors = GeminiBrand.stops,
                ringSpread = idle * 7f,
                ringAlpha = 0.18f,
                // Closed. A mouth moving with no voice behind it is the avatar
                // miming, and the mouth's whole job in this app is to follow
                // real audio.
                mouthOpen = 0f,
                eyeOpen = blink.value,
                showFace = true,
                struckThrough = false,
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

private const val BREATH_MS = 2_600
private const val WAVE_MS = 1_900
private const val BLINK_MIN_MS = 2_400L
private const val BLINK_JITTER_MS = 3_200L
