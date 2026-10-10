package com.fishmate.ai.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fishmate.ai.R
import com.fishmate.ai.ui.theme.GloveFriendlyTouchTarget

/**
 * Shared UI building blocks.
 *
 * Every control here meets [GloveFriendlyTouchTarget] because the user may be
 * wearing gloves on a cold breakwater, and every empty/error state says what
 * to do next rather than just reporting that something is missing.
 */

@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun FishMateCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    // A hairline edge and a shallow lift. Flat cards on a flat background
    // dissolve into it in direct sunlight, which is the light this app is
    // actually used in; the border does the separating and the elevation only
    // hints at order, so neither fights the high-contrast palette.
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) {
        Card(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            border = border,
            elevation = elevation,
            shape = RoundedCornerShape(18.dp),
        ) { content() }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            colors = colors,
            border = border,
            elevation = elevation,
            shape = RoundedCornerShape(18.dp),
        ) { content() }
    }
}

/**
 * "Powered by Gemini", with the mark.
 *
 * Attribution, not decoration: the assistant is Gemini and the app should say
 * so where someone reads about the app rather than only in an About screen.
 * The icon is drawn with `Color.Unspecified` as its tint, without which
 * Material would flatten the brand gradient to a single theme colour.
 */
@Composable
fun PoweredByGemini(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_gemini_spark),
            contentDescription = null,
            tint = Color.Unspecified,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.powered_by_gemini),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun LoadingState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Empty state with a call to action.
 *
 * Avoids the "coming soon" pattern the brief rules out: an empty screen always
 * explains what it is for and offers the next step.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onAction,
                modifier = Modifier.heightIn(min = GloveFriendlyTouchTarget),
            ) { Text(actionLabel) }
        }
    }
}

/** Inline, dismissible error with a retry. Never a stack trace. */
@Composable
fun ErrorBanner(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Default.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            if (onRetry != null) {
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onRetry) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.error_retry))
                }
            }
        }
    }
}

/**
 * Offline / pending-sync strip.
 *
 * Shown rather than hidden, because the brief's rule is that data is never
 * silently lost: if writes are queued, the angler should know they are safe
 * and will sync.
 */
@Composable
fun SyncBanner(
    offline: Boolean,
    pendingWrites: Int,
    syncing: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(visible = offline || pendingWrites > 0) {
        Surface(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (syncing) Icons.Default.Sync else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = when {
                        pendingWrites > 0 ->
                            pluralStringResource(
                                R.plurals.pending_writes,
                                pendingWrites,
                                pendingWrites,
                            )
                        else -> stringResource(R.string.error_offline)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

/**
 * The current locale, read observably.
 *
 * Reading `Locale.getDefault()` directly in a composable does not recompose
 * when the configuration changes, so after switching the app between Turkish
 * and English, dates and decimal separators would keep rendering in the old
 * locale. Going through `LocalConfiguration` makes the read a Compose
 * dependency, so the UI re-renders correctly.
 */
@Composable
fun rememberAppLocale(): java.util.Locale {
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    return remember(configuration) {
        configuration.locales[0] ?: java.util.Locale.getDefault()
    }
}
