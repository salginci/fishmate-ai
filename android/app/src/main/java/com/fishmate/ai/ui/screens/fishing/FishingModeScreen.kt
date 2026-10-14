package com.fishmate.ai.ui.screens.fishing

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PhonelinkRing
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.BuildConfig
import com.fishmate.ai.R
import android.graphics.BitmapFactory
import com.fishmate.ai.camera.PhoneCameraSource
import com.fishmate.ai.live.CameraPreviewFrame
import com.fishmate.ai.audio.AudioRoute
import com.fishmate.ai.live.AssistantPhase
import com.fishmate.ai.live.TranscriptLine
import com.fishmate.ai.live.TranscriptSpeaker
import com.fishmate.ai.location.LocationPrecision
import com.fishmate.ai.service.FishingModeService
import com.fishmate.ai.ui.theme.GloveFriendlyTouchTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Fishing Mode.
 *
 * The product is the voice conversation, so this screen is deliberately almost
 * empty: a state orb, a one-line status, and four large controls. There is no
 * chat interface in the middle, because the angler is holding a rod and cannot
 * read one.
 *
 * The transcript is available but collapsed by default — useful for showing the
 * conversation to someone, or for reading back a detail, not for driving the
 * interaction.
 */
@Composable
fun FishingModeScreen(
    onExit: () -> Unit,
    cameraPermissionGranted: Boolean,
    locationPrecision: LocationPrecision,
    viewModel: FishingModeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activeSession by viewModel.activeSession.collectAsStateWithLifecycle()
    val previewFrame by viewModel.previewFrame.collectAsStateWithLifecycle()
    val showTranslation by viewModel.showTranslation.collectAsStateWithLifecycle()
    val subtitleLanguage by viewModel.subtitleLanguage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Camera is attached only when permitted; otherwise the session is
    // voice-only and every camera control is hidden rather than dead.
    DisposableEffect(cameraPermissionGranted) {
        if (cameraPermissionGranted) {
            viewModel.attachCamera(PhoneCameraSource(context, lifecycleOwner))
        }
        onDispose { }
    }

    LaunchedEffect(Unit) {
        // The foreground service is what makes "phone in pocket" legal and
        // reliable; it starts with the session and stops with it.
        FishingModeService.start(context)
        viewModel.start(
            cameraEnabled = false, // voice first; the angler opts into video
            locationPrecision = locationPrecision,
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stop()
            FishingModeService.stop(context)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ---- top: session context, kept to one quiet line -------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeSession?.locationLabel
                            ?: stringResource(R.string.fishing_ready),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    activeSession?.let { session ->
                        Text(
                            text = "${stringResource(R.string.session_catches)}: " +
                                "${session.catchCount}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                FilledIconButton(
                    onClick = onExit,
                    modifier = Modifier.size(GloveFriendlyTouchTarget),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.fishing_end),
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- the avatar: the one thing the angler glances at ------------
            // Tapping the assistant stops it. Only while it is actually
            // talking — a tap at any other moment has nothing to interrupt,
            // and a control that sometimes does nothing is worse than one
            // that is plainly absent.
            val speaking = state.phase == AssistantPhase.SPEAKING
            val interruptLabel = stringResource(R.string.fishing_tap_to_interrupt)
            AssistantAvatar(
                phase = state.phase,
                isMuted = state.isMuted,
                getLevel = viewModel::outputLevel,
                modifier = if (speaking) {
                    Modifier
                        .clip(CircleShape)
                        .clickable(
                            onClickLabel = interruptLabel,
                            role = Role.Button,
                            onClick = viewModel::interrupt,
                        )
                } else {
                    Modifier
                },
            )

            Spacer(Modifier.height(24.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // The mark sits with the status rather than in a corner: this
                // screen is the assistant, and the status line is the one
                // thing on it that is always read.
                Icon(
                    painter = painterResource(R.drawable.ic_gemini_spark),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = statusLabel(state.statusKey),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = state.errorMessage
                    ?: if (state.phase == AssistantPhase.ERROR) {
                        stringResource(R.string.error_gemini_unreachable)
                    } else if (state.phase == AssistantPhase.LISTENING) {
                        stringResource(R.string.fishing_ask_anything)
                    } else if (speaking) {
                        // The line that was missing. Cutting in by voice on a
                        // loudspeaker is unreliable by measurement, not by
                        // oversight, and the tap is the path that always
                        // works — but only if the angler knows it is there.
                        // One angler asked a question over the assistant, had
                        // it swallowed, and sat waiting a full minute for an
                        // answer the server was never sent.
                        interruptLabel
                    } else {
                        ""
                    },
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.errorMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
                modifier = Modifier.heightIn(min = 44.dp),
            )

            // Debug builds only: the two numbers that tell you whether the
            // echo loop is back. An angler must not see these — they are the
            // Android counterpart of the web client's counter strip, which
            // exists to be read during a real session on a loudspeaker rather
            // than guessed at from a log afterwards.
            if (BuildConfig.DEBUG && (state.repeatedSentences > 0 || state.interruptions > 0)) {
                Text(
                    text = "repeats ${state.repeatedSentences} · cut-ins ${state.interruptions}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.repeatedSentences > 0) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    textAlign = TextAlign.Center,
                )
            }

            // Tool activity is worth surfacing: it is the visible proof the
            // assistant is consulting real data rather than guessing.
            AnimatedVisibility(visible = state.phase == AssistantPhase.TOOL_CALL) {
                ToolActivityChip(state.toolActivity.lastOrNull())
            }

            Spacer(Modifier.weight(1f))

            // ---- what the assistant sees ----------------------------------
            // "Is the camera actually on?" is otherwise unanswerable: frames
            // stream silently and the only feedback was a counter.
            AnimatedVisibility(visible = state.cameraEnabled && previewFrame != null) {
                previewFrame?.let { frame ->
                    CameraVision(
                        frame = frame,
                        framesSent = state.framesSent,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .padding(bottom = 12.dp),
                    )
                }
            }

            // ---- transcript (collapsed, optional) -------------------------
            if (state.transcript.isNotEmpty()) {
                LiveTranscript(
                    lines = state.transcript,
                    showTranslation = showTranslation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (showTranslation) 180.dp else 140.dp),
                )
                Spacer(Modifier.height(16.dp))
            }

            // ---- controls: four large targets -----------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The old icon here was Material's Translate glyph, which
                // draws a Han character next to an A. On a Turkish phone that
                // is not "translation", it is two symbols from a language
                // nobody in this conversation is speaking. A subtitles icon
                // says what the button actually does: show a second line under
                // what was said.
                ControlToggle(
                    icon = if (showTranslation) {
                        Icons.Default.Subtitles
                    } else {
                        Icons.Default.SubtitlesOff
                    },
                    label = stringResource(R.string.fishing_subtitles),
                    checked = showTranslation,
                    onClick = viewModel::toggleTranslation,
                )

                ControlToggle(
                    icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = stringResource(R.string.fishing_mic),
                    checked = !state.isMuted,
                    onClick = viewModel::toggleMute,
                )

                // Hands-free output. The label is the route the system
                // actually reports, not a stored preference, so an earbud
                // that runs flat mid-cast stops claiming Bluetooth the moment
                // the audio moves to the loudspeaker.
                ControlToggle(
                    icon = when {
                        !state.handsFree -> Icons.Default.PhonelinkRing
                        state.audioRoute == AudioRoute.BLUETOOTH -> Icons.Default.Bluetooth
                        state.audioRoute == AudioRoute.WIRED_HEADSET -> Icons.Default.Headphones
                        else -> Icons.Default.VolumeUp
                    },
                    label = stringResource(
                        when {
                            !state.handsFree -> R.string.fishing_handsfree_off
                            state.audioRoute == AudioRoute.BLUETOOTH -> R.string.fishing_route_bluetooth
                            state.audioRoute == AudioRoute.WIRED_HEADSET -> R.string.fishing_route_headset
                            else -> R.string.fishing_route_speaker
                        },
                    ),
                    checked = state.handsFree,
                    onClick = viewModel::toggleHandsFree,
                    accessibleName = stringResource(R.string.fishing_handsfree),
                )

                if (cameraPermissionGranted) {
                    ControlToggle(
                        icon = if (state.cameraEnabled) {
                            Icons.Default.Visibility
                        } else {
                            Icons.Default.VisibilityOff
                        },
                        label = stringResource(R.string.fishing_camera),
                        checked = state.cameraEnabled,
                        onClick = viewModel::toggleCamera,
                    )

                    if (state.cameraEnabled) {
                        ControlAction(
                            icon = Icons.Default.Cameraswitch,
                            label = stringResource(R.string.fishing_switch_camera),
                            onClick = viewModel::switchCamera,
                        )
                    } else {
                        // One-shot capture: far cheaper than streaming when
                        // the angler just wants "look at this".
                        ControlAction(
                            icon = Icons.Default.PhotoCamera,
                            label = stringResource(R.string.fishing_look_at_this),
                            onClick = viewModel::sendLookAtThis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The assistant's eye: the most recent frame that went to the model.
 *
 * Deliberately not a CameraX `PreviewView`. A preview stream shows what the
 * sensor sees, which is not the same thing — the frames sent are throttled,
 * downscaled and JPEG-compressed, and are skipped entirely while the model is
 * speaking. Decoding the actual bytes means the picture on screen is the
 * picture Gemini was given, framing, resolution and staleness included, so
 * "why did it not see my lure?" has an answer the angler can act on.
 *
 * Decoding happens off the main thread. At the default half a frame per second
 * that is one small JPEG every two seconds, which is cheaper than running a
 * second camera pipeline for the preview.
 */
@Composable
private fun CameraVision(
    frame: CameraPreviewFrame,
    framesSent: Int,
    modifier: Modifier = Modifier,
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, frame) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            bitmap?.let { image ->
                Image(
                    bitmap = image,
                    contentDescription = stringResource(R.string.fishing_vision_description),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        // Dimmed while the frame is not being sent, so a
                        // paused stream reads as paused rather than frozen.
                        .alpha(if (frame.sent) 1f else 0.45f),
                )
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.55f),
            ) {
                Text(
                    text = if (frame.sent) {
                        pluralStringResource(R.plurals.fishing_vision_sent, framesSent, framesSent)
                    } else {
                        stringResource(R.string.fishing_vision_paused)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun ToolActivityChip(toolName: String?) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(100),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        Text(
            text = toolName?.replace('_', ' ') ?: stringResource(R.string.fishing_status_tool),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun LiveTranscript(
    lines: List<TranscriptLine>,
    showTranslation: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow the conversation automatically; the angler is not scrolling.
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(16.dp),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(lines, key = { it.id }) { line ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (line.speaker) {
                            TranscriptSpeaker.USER -> MaterialTheme.colorScheme.onSurfaceVariant
                            TranscriptSpeaker.ASSISTANT -> MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (line.speaker == TranscriptSpeaker.ASSISTANT) {
                            FontWeight.Medium
                        } else {
                            FontWeight.Normal
                        },
                    )
                    // Absent until the translation arrives. Nothing is shown
                    // while it is pending: a spinner per line would flicker
                    // through the whole conversation.
                    if (showTranslation && line.translation != null) {
                        Text(
                            text = line.translation,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontStyle = FontStyle.Italic,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A control that is either on or off.
 *
 * Filled means on, every time. That consistency is the whole point: the bar
 * used to draw a one-shot action and a switched-off toggle identically, so
 * "Look at this" and a disabled camera looked like the same kind of thing, and
 * the fill meant something different on each button -- on the microphone it
 * meant "the mic is live" while the label next to it said "Mute", which reads
 * as the exact opposite.
 *
 * The label is the noun, never the verb. "Mute" answers what a tap does but
 * not what is true now; "Mic" plus a slashed icon plus the fill answers both,
 * and a glove-sized target with three redundant cues survives a glance from a
 * boat. [Role.Switch] and [stateDescription] give TalkBack the same two facts.
 */
@Composable
private fun ControlToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    onClick: () -> Unit,
    /** Spoken name, when the visible label is a value rather than the thing. */
    accessibleName: String = label,
) {
    val state = stringResource(if (checked) R.string.fishing_on else R.string.fishing_off)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            modifier = Modifier
                .size(GloveFriendlyTouchTarget + 8.dp)
                .semantics {
                    role = Role.Switch
                    stateDescription = state
                },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (checked) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                contentColor = if (checked) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            ),
        ) {
            Icon(icon, contentDescription = accessibleName, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A control that does something once and has no state to show.
 *
 * Outlined rather than filled, so it can never be mistaken for a toggle that
 * happens to be off. Its label stays a verb for the same reason.
 */
@Composable
private fun ControlAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        OutlinedIconButton(
            onClick = onClick,
            modifier = Modifier
                .size(GloveFriendlyTouchTarget + 8.dp)
                .semantics { role = Role.Button },
            colors = IconButtonDefaults.outlinedIconButtonColors(
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun statusLabel(key: String): String = stringResource(
    when (key) {
        "connecting" -> R.string.fishing_status_connecting
        "reconnecting" -> R.string.fishing_status_reconnecting
        "listening" -> R.string.fishing_status_listening
        "thinking" -> R.string.fishing_status_thinking
        "speaking" -> R.string.fishing_status_speaking
        "watching" -> R.string.fishing_status_watching
        "tool" -> R.string.fishing_status_tool
        "muted" -> R.string.fishing_status_muted
        "error" -> R.string.fishing_status_error
        else -> R.string.fishing_status_idle
    },
)
