package com.fishmate.ai.ui.screens.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.remote.FishMateApi
import com.fishmate.ai.data.remote.dto.DemoScenarioDto
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.data.repository.apiCall
import com.fishmate.ai.ui.components.FishMateCard
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DemoUiState(
    val scenarios: List<DemoScenarioDto> = emptyList(),
    val fallback: String = "",
    val status: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class DemoViewModel @Inject constructor(
    private val api: FishMateApi,
    private val repository: FishingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(DemoUiState())
    val state: StateFlow<DemoUiState> = _state.asStateFlow()

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            when (val result = apiCall("demo") { api.demoScenarios() }) {
                is AppResult.Ok -> _state.value = _state.value.copy(
                    scenarios = result.value.scenarios,
                    fallback = result.value.fallback,
                )
                is AppResult.Err -> _state.value =
                    _state.value.copy(status = result.error.message)
            }
        }
    }

    /** Seed deterministic history so the memory scenarios have real data. */
    fun seed(reset: Boolean) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = true, status = null)
            val result = apiCall("demo") { api.demoSeed(reset) }
            // Pull the seeded rows into Room so every screen shows them.
            repository.refreshAll()
            _state.value = _state.value.copy(
                busy = false,
                status = when (result) {
                    is AppResult.Ok -> "History seeded and synced."
                    is AppResult.Err -> result.error.message
                },
            )
        }
    }

    /** Swap the weather provider for the deterministic one. */
    fun useMockWeather(enabled: Boolean) {
        viewModelScope.launch {
            val result = apiCall("demo") { api.demoMockWeather(enabled) }
            _state.value = _state.value.copy(
                status = when (result) {
                    is AppResult.Ok ->
                        if (enabled) "Weather is now deterministic." else "Live weather restored."
                    is AppResult.Err -> result.error.message
                },
            )
        }
    }
}

/**
 * Presentation runsheet.
 *
 * This screen is a teleprompter, not a different product: every scenario runs
 * through the same Gemini Live session and the same tool layer as normal use.
 * What Demo Mode controls is only the two things a stage cannot guarantee —
 * external data and existing history (docs/DEMO.md).
 */
@Composable
fun DemoScreen(viewModel: DemoViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text("Demo Mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Gemini Live is real in every scenario. Only weather and seeded " +
                "history are made deterministic.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.seed(false) }, enabled = !state.busy) {
                Text("Seed history")
            }
            OutlinedButton(onClick = { viewModel.seed(true) }, enabled = !state.busy) {
                Text("Reset + seed")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.useMockWeather(true) }) {
                Text("Deterministic weather")
            }
            OutlinedButton(onClick = { viewModel.useMockWeather(false) }) {
                Text("Live weather")
            }
        }

        state.status?.let {
            Spacer(Modifier.height(12.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        Spacer(Modifier.height(24.dp))
        state.scenarios.forEach { scenario ->
            FishMateCard(modifier = Modifier.padding(bottom = 10.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape,
                        ) {
                            Text(
                                text = scenario.id.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = scenario.title,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "“${scenario.say}”",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = scenario.capability,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = scenario.expect,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (state.fallback.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("If something fails", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = state.fallback,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
