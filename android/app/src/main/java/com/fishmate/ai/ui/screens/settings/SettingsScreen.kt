package com.fishmate.ai.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.fishmate.ai.BuildConfig
import com.fishmate.ai.R
import com.fishmate.ai.data.remote.dto.PreferencesUpdate
import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.ui.components.FishMateCard
import com.fishmate.ai.live.LIVE_VOICES
import com.fishmate.ai.ui.components.SectionHeader
import com.fishmate.ai.ui.components.rememberAppLocale
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val language: String = "en",
    val locationPrecision: String = "approximate",
    val cameraFps: Double = 0.5,
    val voiceName: String? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val authRepository: AuthRepository,
) : ViewModel() {

    private val _local = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _local.asStateFlow()

    val displayName: StateFlow<String?> = authRepository.displayName
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            val profile = authRepository.refreshProfile().getOrNull()
            profile?.preferences?.let { prefs ->
                _local.value = SettingsUiState(
                    language = prefs.language,
                    locationPrecision = prefs.locationPrecision,
                    cameraFps = prefs.cameraFps,
                    voiceName = prefs.voiceName,
                )
            }
        }
    }

    fun setLanguage(value: String) {
        _local.value = _local.value.copy(language = value)
        push(PreferencesUpdate(language = value))
    }

    fun setLocationPrecision(value: String) {
        _local.value = _local.value.copy(locationPrecision = value)
        push(PreferencesUpdate(locationPrecision = value))
    }

    /**
     * Choose the assistant's voice.
     *
     * Stored as a preference rather than passed per session: the backend falls
     * back to it when minting a Live token, so nothing on the device needs to
     * know about voices. `null` means "whatever the server default is".
     */
    fun setVoice(value: String?) {
        _local.value = _local.value.copy(voiceName = value)
        push(PreferencesUpdate(voiceName = value ?: ""))
    }

    fun setCameraFps(value: Double) {
        _local.value = _local.value.copy(cameraFps = value)
        push(PreferencesUpdate(cameraFps = value))
    }

    private fun push(update: PreferencesUpdate) {
        viewModelScope.launch { authRepository.updatePreferences(update) }
    }

    fun signOut(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.logout()
            onDone()
        }
    }

    fun deleteAccount(onDone: () -> Unit) {
        viewModelScope.launch {
            authRepository.deleteAccount()
            onDone()
        }
    }
}

@Composable
fun SettingsScreen(
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDemo: () -> Unit,
    onSignedOut: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val name by viewModel.displayName.collectAsStateWithLifecycle()
    val locale = rememberAppLocale()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        name?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(start = 20.dp, top = 16.dp, end = 20.dp),
            )
        }

        // ---- language ----------------------------------------------------
        SectionHeader(stringResource(R.string.settings_language))
        FishMateCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                // English first: it is the product default.
                listOf("en" to "English", "tr" to "Türkçe").forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.language == code,
                            onClick = { viewModel.setLanguage(code) },
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ---- location ----------------------------------------------------
        SectionHeader(stringResource(R.string.settings_location))
        FishMateCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                listOf(
                    "precise" to stringResource(R.string.settings_location_precise),
                    "approximate" to stringResource(R.string.settings_location_approximate),
                    "none" to stringResource(R.string.settings_location_none),
                ).forEach { (code, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.locationPrecision == code,
                            onClick = { viewModel.setLocationPrecision(code) },
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }

        // ---- voice ---------------------------------------------------------
        SectionHeader(stringResource(R.string.settings_voice))
        FishMateCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_voice_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = state.voiceName.isNullOrBlank(),
                        onClick = { viewModel.setVoice(null) },
                        label = { Text(stringResource(R.string.settings_voice_default)) },
                    )
                    LIVE_VOICES.forEach { voice ->
                        FilterChip(
                            selected = state.voiceName == voice,
                            onClick = { viewModel.setVoice(voice) },
                            label = { Text(voice) },
                        )
                    }
                }
            }
        }

        // ---- camera / cost ------------------------------------------------
        SectionHeader(stringResource(R.string.settings_camera_rate))
        FishMateCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.settings_camera_rate_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = String.format(locale, "%.1f fps", state.cameraFps),
                    style = MaterialTheme.typography.titleMedium,
                )
                Slider(
                    value = state.cameraFps.toFloat(),
                    onValueChange = { viewModel.setCameraFps(it.toDouble()) },
                    valueRange = 0.2f..2.0f,
                    steps = 8,
                )
            }
        }

        // ---- links --------------------------------------------------------
        SectionHeader("")
        FishMateCard(modifier = Modifier.padding(horizontal = 20.dp)) {
            Column {
                SettingsLink(stringResource(R.string.settings_privacy), onOpenPrivacy)
                SettingsLink(stringResource(R.string.settings_about), onOpenAbout)
                if (BuildConfig.DEBUG) {
                    SettingsLink(stringResource(R.string.settings_demo), onOpenDemo)
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        TextButton(
            onClick = { viewModel.signOut(onSignedOut) },
            modifier = Modifier.padding(horizontal = 20.dp),
        ) { Text(stringResource(R.string.settings_sign_out)) }

        TextButton(
            onClick = { confirmDelete = true },
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(R.string.settings_delete_account),
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = "v${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.settings_delete_account)) },
            text = {
                Text(
                    "This permanently deletes your account, sessions, catches, tackle " +
                        "and saved spots. It cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        viewModel.deleteAccount(onSignedOut)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingsLink(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ChevronRight, contentDescription = null)
        }
    }
}
