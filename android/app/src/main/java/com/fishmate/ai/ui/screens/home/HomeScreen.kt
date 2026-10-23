package com.fishmate.ai.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Backpack
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.R
import com.fishmate.ai.core.AppError
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.ui.components.ErrorBanner
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.ui.components.PoweredByGemini
import com.fishmate.ai.ui.components.SectionHeader
import com.fishmate.ai.ui.components.SyncBanner
import com.fishmate.ai.ui.components.rememberAppLocale
import com.fishmate.ai.ui.theme.GloveFriendlyTouchTarget
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Home.
 *
 * One dominant action — start fishing — then only what is genuinely useful
 * before you cast: what it's doing outside, what you caught recently, and how
 * much gear you have on record. Deliberately not a dashboard.
 */
@Composable
fun HomeScreen(
    onStartFishing: () -> Unit,
    onOpenTackle: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenCatch: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = 12.dp,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Text(
                    text = greeting(state.displayName),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = stringResource(R.string.onboarding_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                PoweredByGemini()
            }
        }

        item {
            SyncBanner(
                // Only a real network failure is called one. Any other
                // reason conditions did not arrive -- a rejected token, a
                // refusing upstream -- is reported by the conditions card in
                // its own words, because telling an angler with four bars
                // that they are offline sends them to fix the wrong thing.
                offline = state.conditionsError?.code == AppError.CODE_OFFLINE &&
                    state.conditions == null,
                pendingWrites = state.pendingWrites,
                syncing = state.refreshing,
            )
        }

        state.error?.let { error ->
            item {
                ErrorBanner(message = error.message, onRetry = viewModel::refresh)
            }
        }

        // ---- the primary action ------------------------------------------
        item {
            StartFishingAvatar(
                activeSession = state.activeSession != null,
                onClick = onStartFishing,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }

        state.activeSession?.let { session ->
            item {
                Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Phishing,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.home_active_session),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                                Text(
                                    text = listOfNotNull(
                                        session.locationLabel,
                                        "${session.catchCount} " +
                                            stringResource(R.string.session_catches),
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            }
                        }
                    }
                }
            }
        }

        // ---- conditions ---------------------------------------------------
        item { SectionHeader(stringResource(R.string.home_conditions)) }
        item {
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                ConditionsCard(state)
            }
        }

        // ---- quick actions ------------------------------------------------
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                QuickAction(
                    icon = Icons.Default.Backpack,
                    label = stringResource(R.string.home_my_tackle),
                    value = state.tackleCount.toString(),
                    onClick = onOpenTackle,
                    modifier = Modifier.weight(1f),
                )
                QuickAction(
                    icon = Icons.Default.History,
                    label = stringResource(R.string.nav_sessions),
                    value = state.lastSession?.let { "" } ?: "",
                    onClick = onOpenSessions,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- recent catches -----------------------------------------------
        item { SectionHeader(stringResource(R.string.home_recent_catches)) }
        if (state.recentCatches.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.home_no_catches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        } else {
            items(state.recentCatches, key = { it.id }) { item ->
                Box(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                    CatchRow(item = item, onClick = { onOpenCatch(item.id) })
                }
            }
        }
    }
}

@Composable
private fun ConditionsCard(state: HomeUiState) {
    val conditions = state.conditions
    val locale = rememberAppLocale()
    FishMateCard {
        Column(modifier = Modifier.padding(16.dp)) {
            if (conditions == null) {
                Text(
                    text = state.conditionsError?.message
                        ?: stringResource(R.string.perm_location_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            Text(
                text = conditions.summary,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                conditions.windSpeedKmh?.let {
                    Metric(
                        Icons.Default.Air,
                        "${conditions.windDirection ?: ""} ${it.toInt()} km/h".trim(),
                        conditions.windBeaufort?.let { bf -> "Bf $bf" },
                    )
                }
                conditions.waveHeightM?.let {
                    Metric(Icons.Default.Waves, String.format(locale, "%.1f m", it), null)
                }
                conditions.waterTempC?.let {
                    Metric(Icons.Default.Thermostat, "${it.toInt()}°C", "su")
                }
            }
            if (!conditions.seaDataAvailable) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // Say so rather than showing a blank where waves would be.
                    text = "Bu konum için deniz verisi yok.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Metric(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    caption: String?,
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(6.dp))
            Text(text = value, style = MaterialTheme.typography.bodyLarge)
        }
        caption?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FishMateCard(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = GloveFriendlyTouchTarget),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(text = label, style = MaterialTheme.typography.titleMedium)
            if (value.isNotBlank()) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun CatchRow(item: CatchEntity, onClick: () -> Unit) {
    FishMateCard(onClick = onClick) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.speciesLabel,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // An unreviewed AI guess is labelled everywhere it appears.
                    if (item.aiConfidence != null && !item.speciesConfirmedByUser) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(100),
                        ) {
                            Text(
                                text = "AI ${(item.aiConfidence * 100).toInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                Text(
                    text = listOfNotNull(
                        item.lengthCm?.let { "${it.toInt()} cm" },
                        item.lureLabel,
                        formatDayNow(item.caughtAtEpochMs),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Format a timestamp for display.
 *
 * The formatter is built per call rather than held in a static field: the app
 * switches between Turkish and English at runtime, and a cached formatter
 * would keep rendering dates in whichever locale happened to be active when
 * the class was first loaded.
 */
internal fun formatDay(epochMs: Long, locale: Locale): String =
    SimpleDateFormat("d MMM HH:mm", locale).format(Date(epochMs))

/** Composable overload that reads the locale observably. */
@Composable
internal fun formatDayNow(epochMs: Long): String = formatDay(epochMs, rememberAppLocale())

@Composable
private fun greeting(name: String): String =
    if (name.isBlank()) stringResource(R.string.app_name) else name
