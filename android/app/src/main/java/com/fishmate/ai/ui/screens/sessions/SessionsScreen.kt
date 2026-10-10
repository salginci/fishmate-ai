package com.fishmate.ai.ui.screens.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.R
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.ui.components.EmptyState
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.ui.screens.home.CatchRow
import com.fishmate.ai.ui.screens.home.formatDayNow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SessionsViewModel @Inject constructor(
    private val repository: FishingRepository,
) : ViewModel() {

    val sessions: StateFlow<List<SessionEntity>> = repository.observeSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun session(id: String) = repository.observeSession(id)

    fun catches(sessionId: String) = repository.observeCatchesForSession(sessionId)

    fun refresh(id: String) {
        viewModelScope.launch { repository.refreshSession(id) }
    }

    fun finish(id: String) {
        viewModelScope.launch { repository.finishSession(id, null) }
    }
}

@Composable
fun SessionsScreen(
    onOpenSession: (String) -> Unit,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val sessions by viewModel.sessions.collectAsStateWithLifecycle()

    if (sessions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Default.History,
                title = stringResource(R.string.nav_sessions),
                body = stringResource(R.string.session_empty),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { session ->
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                SessionRow(session = session, onClick = { onOpenSession(session.id) })
            }
        }
    }
}

@Composable
private fun SessionRow(session: SessionEntity, onClick: () -> Unit) {
    FishMateCard(onClick = onClick) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = session.locationLabel ?: stringResource(R.string.nav_sessions),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                if (session.status == "active") {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(100),
                    ) {
                        Text(
                            text = stringResource(R.string.home_active_session),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = listOfNotNull(
                    formatDayNow(session.startedAtEpochMs),
                    durationLabel(session),
                    "${session.catchCount} ${stringResource(R.string.session_catches)}",
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            session.conditionsSummary?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Session detail: the generated report, verbatim.
 *
 * The report is produced deterministically by the backend and stored, so it
 * reads identically here, offline, and in the voice summary the assistant
 * gives — no second, divergent rendering of the same facts.
 */
@Composable
fun SessionDetailScreen(
    sessionId: String,
    viewModel: SessionsViewModel = hiltViewModel(),
) {
    val session by viewModel.session(sessionId)
        .collectAsStateWithLifecycle(initialValue = null)
    val catches by viewModel.catches(sessionId)
        .collectAsStateWithLifecycle(initialValue = emptyList<CatchEntity>())

    androidx.compose.runtime.LaunchedEffect(sessionId) { viewModel.refresh(sessionId) }

    val current = session ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(
            text = current.locationLabel ?: stringResource(R.string.session_report),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = listOfNotNull(
                formatDayNow(current.startedAtEpochMs),
                durationLabel(current)?.let {
                    "${stringResource(R.string.session_duration)}: $it"
                },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (current.status == "active") {
            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(onClick = { viewModel.finish(sessionId) }) {
                Text(stringResource(R.string.session_finish))
            }
        }

        current.summary?.let { report ->
            Spacer(Modifier.height(20.dp))
            FishMateCard {
                Text(
                    text = report,
                    // Monospace preserves the report's alignment.
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (catches.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.session_catches),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            catches.forEach { item ->
                Box(modifier = Modifier.padding(vertical = 4.dp)) {
                    CatchRow(item = item, onClick = {})
                }
            }
        }
    }
}

private fun durationLabel(session: SessionEntity): String? {
    val end = session.endedAtEpochMs ?: return null
    val minutes = ((end - session.startedAtEpochMs) / 60_000L).coerceAtLeast(0)
    return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
