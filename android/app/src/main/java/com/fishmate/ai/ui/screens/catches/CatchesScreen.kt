package com.fishmate.ai.ui.screens.catches

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fishmate.ai.R
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.remote.dto.CatchUpdate
import com.fishmate.ai.data.remote.dto.SpeciesDto
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.ui.components.EmptyState
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.ui.screens.home.CatchRow
import com.fishmate.ai.ui.screens.home.formatDayNow
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class CatchesViewModel @Inject constructor(
    private val repository: FishingRepository,
) : ViewModel() {

    val catches: StateFlow<List<CatchEntity>> = repository.observeCatches()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _species = MutableStateFlow<List<SpeciesDto>>(emptyList())
    val species: StateFlow<List<SpeciesDto>> = _species.asStateFlow()

    init {
        viewModelScope.launch {
            (repository.speciesCatalogue() as? AppResult.Ok)?.let { _species.value = it.value }
        }
    }

    fun catchById(id: String) = repository.observeCatch(id)

    fun update(id: String, update: CatchUpdate) {
        viewModelScope.launch { repository.updateCatch(id, update) }
    }

    fun confirmSpecies(id: String) {
        viewModelScope.launch { repository.confirmSpecies(id) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.deleteCatch(id) }
    }
}

@Composable
fun CatchLogScreen(
    onOpenCatch: (String) -> Unit,
    viewModel: CatchesViewModel = hiltViewModel(),
) {
    val catches by viewModel.catches.collectAsStateWithLifecycle()

    if (catches.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Default.Phishing,
                title = stringResource(R.string.nav_catches),
                body = stringResource(R.string.catch_empty),
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(catches, key = { it.id }) { item ->
            Box(modifier = Modifier.padding(horizontal = 20.dp)) {
                CatchRow(item = item, onClick = { onOpenCatch(item.id) })
            }
        }
    }
}

/**
 * Catch detail.
 *
 * Built around the rule that AI output must never be immutable: an
 * AI-identified species is shown as a *claim* with its confidence and the
 * visual cues the model used, plus a prominent disclaimer and one-tap confirm
 * or change.
 */
@Composable
fun CatchDetailScreen(
    catchId: String,
    onDeleted: () -> Unit,
    viewModel: CatchesViewModel = hiltViewModel(),
) {
    val item by viewModel.catchById(catchId).collectAsStateWithLifecycle(initialValue = null)
    val species by viewModel.species.collectAsStateWithLifecycle()
    var showSpeciesPicker by remember { mutableStateOf(false) }
    var editingLength by remember { mutableStateOf<String?>(null) }

    val current = item ?: return

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
    ) {
        Text(text = current.speciesLabel, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatDayNow(current.caughtAtEpochMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- the AI identification block ---------------------------------
        if (current.aiConfidence != null && !current.speciesConfirmedByUser) {
            Spacer(Modifier.height(16.dp))
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "AI: ${current.speciesLabel} " +
                            "(${(current.aiConfidence * 100).toInt()}%)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    current.aiReasoning?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = stringResource(R.string.catch_ai_disclaimer),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.confirmSpecies(catchId) }) {
                            Text(stringResource(R.string.catch_confirm_species))
                        }
                        OutlinedButton(onClick = { showSpeciesPicker = true }) {
                            Text(stringResource(R.string.catch_change_species))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        FishMateCard {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailRow(stringResource(R.string.catch_length), current.lengthCm?.let { "${it.toInt()} cm" })
                DetailRow(stringResource(R.string.catch_weight), current.weightG?.let { "${it.toInt()} g" })
                DetailRow(stringResource(R.string.catch_lure), current.lureLabel)
                DetailRow("Technique", current.technique)
                DetailRow("Location", current.locationLabel)
                DetailRow("Conditions", current.weatherSummary)
                DetailRow(
                    stringResource(R.string.catch_released),
                    if (current.released) "Yes" else "No",
                )
                current.notes?.let { DetailRow("Notes", it) }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSpeciesPicker = true }) {
                Text(stringResource(R.string.catch_change_species))
            }
            OutlinedButton(
                onClick = { editingLength = current.lengthCm?.toInt()?.toString() ?: "" },
            ) { Text(stringResource(R.string.catch_length)) }
        }

        Spacer(Modifier.height(24.dp))

        TextButton(
            onClick = {
                viewModel.delete(catchId)
                onDeleted()
            },
        ) {
            Icon(Icons.Default.Delete, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Delete this catch")
        }
    }

    if (showSpeciesPicker) {
        AlertDialog(
            onDismissRequest = { showSpeciesPicker = false },
            title = { Text(stringResource(R.string.catch_change_species)) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    species.forEach { option ->
                        TextButton(
                            onClick = {
                                viewModel.update(catchId, CatchUpdate(species = option.key))
                                showSpeciesPicker = false
                            },
                        ) { Text(option.label) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeciesPicker = false }) { Text("Cancel") }
            },
        )
    }

    editingLength?.let { value ->
        var text by remember(value) { mutableStateOf(value) }
        AlertDialog(
            onDismissRequest = { editingLength = null },
            title = { Text(stringResource(R.string.catch_length)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        text.toDoubleOrNull()?.let {
                            viewModel.update(catchId, CatchUpdate(lengthCm = it))
                        }
                        editingLength = null
                    },
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { editingLength = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.padding(vertical = 5.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}
