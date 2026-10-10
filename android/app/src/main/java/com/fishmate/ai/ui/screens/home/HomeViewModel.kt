package com.fishmate.ai.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fishmate.ai.core.AppError
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.local.entity.CatchEntity
import com.fishmate.ai.data.local.entity.SessionEntity
import com.fishmate.ai.data.remote.dto.ConditionsDto
import com.fishmate.ai.data.remote.dto.SessionStartRequest
import com.fishmate.ai.data.repository.AuthRepository
import com.fishmate.ai.data.repository.FishingRepository
import com.fishmate.ai.location.LocationProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val displayName: String = "",
    val activeSession: SessionEntity? = null,
    val lastSession: SessionEntity? = null,
    val recentCatches: List<CatchEntity> = emptyList(),
    val tackleCount: Int = 0,
    val totalCatches: Int = 0,
    val conditions: ConditionsDto? = null,
    val conditionsError: String? = null,
    val pendingWrites: Int = 0,
    val refreshing: Boolean = false,
    val error: AppError? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val fishingRepository: FishingRepository,
    private val authRepository: AuthRepository,
    private val locationProvider: LocationProvider,
) : ViewModel() {

    private val _volatile = MutableStateFlow(VolatileState())
    private data class VolatileState(
        val conditions: ConditionsDto? = null,
        val conditionsError: String? = null,
        val refreshing: Boolean = false,
        val error: AppError? = null,
    )

    /**
     * Local data first, network second.
     *
     * Everything on Home except conditions comes from Room, so the screen is
     * fully populated before any request completes — and stays populated with
     * no signal at all.
     */
    val state: StateFlow<HomeUiState> = combine(
        fishingRepository.observeActiveSession(),
        fishingRepository.observeSessions(),
        fishingRepository.observeRecentCatches(5),
        fishingRepository.observeTackleCount(),
        combine(
            fishingRepository.observeCatchCount(),
            fishingRepository.observePendingWrites(),
            authRepository.displayName,
            _volatile,
        ) { catchCount, pending, name, volatileState ->
            arrayOf(catchCount, pending, name, volatileState)
        },
    ) { active, sessions, catches, tackleCount, rest ->
        @Suppress("UNCHECKED_CAST")
        val catchCount = rest[0] as Int
        val pending = rest[1] as Int
        val name = rest[2] as String?
        val volatileState = rest[3] as VolatileState

        HomeUiState(
            displayName = name ?: "",
            activeSession = active,
            lastSession = sessions.firstOrNull { it.id != active?.id },
            recentCatches = catches,
            tackleCount = tackleCount,
            totalCatches = catchCount,
            conditions = volatileState.conditions,
            conditionsError = volatileState.conditionsError,
            pendingWrites = pending,
            refreshing = volatileState.refreshing,
            error = volatileState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _volatile.value = _volatile.value.copy(refreshing = true, error = null)

            // Replay anything queued offline before pulling fresh data, so the
            // refresh does not overwrite a local write that never shipped.
            runCatching { fishingRepository.syncPending() }

            val result = fishingRepository.refreshAll()
            loadConditions()

            _volatile.value = _volatile.value.copy(
                refreshing = false,
                error = (result as? AppResult.Err)?.error,
            )
        }
    }

    private suspend fun loadConditions() {
        val position = locationProvider.lastKnown()
        if (position == null) {
            // No location is not an error: the rest of Home still works, and
            // the UI explains that conditions need location.
            _volatile.value = _volatile.value.copy(conditions = null, conditionsError = null)
            return
        }
        when (val result = fishingRepository.conditions(position.latitude, position.longitude)) {
            is AppResult.Ok -> _volatile.value = _volatile.value.copy(
                conditions = result.value,
                conditionsError = null,
            )
            is AppResult.Err -> _volatile.value = _volatile.value.copy(
                conditions = null,
                conditionsError = result.error.message,
            )
        }
    }

    /** Start a session from Home, with whatever context we already have. */
    fun startSession(onStarted: (String) -> Unit) {
        viewModelScope.launch {
            val position = locationProvider.lastKnown()
            val result = fishingRepository.startSession(
                SessionStartRequest(
                    latitude = position?.latitude,
                    longitude = position?.longitude,
                ),
            )
            when (result) {
                is AppResult.Ok -> onStarted(result.value)
                is AppResult.Err ->
                    _volatile.value = _volatile.value.copy(error = result.error)
            }
        }
    }

    fun dismissError() {
        _volatile.value = _volatile.value.copy(error = null)
    }
}
