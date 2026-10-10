package com.fishmate.ai.ui.screens.tackle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fishmate.ai.core.AppError
import com.fishmate.ai.core.AppResult
import com.fishmate.ai.data.local.entity.TackleEntity
import com.fishmate.ai.data.remote.dto.TackleItemCreate
import com.fishmate.ai.data.remote.dto.TackleTemplateDto
import com.fishmate.ai.data.repository.FishingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class TackleViewModel @Inject constructor(
    private val repository: FishingRepository,
) : ViewModel() {

    /** Grouped by category so the list reads like a real tackle box. */
    val grouped: StateFlow<Map<String, List<TackleEntity>>> =
        repository.observeTackle()
            .map { items -> items.groupBy { it.category } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _templates = MutableStateFlow<List<TackleTemplateDto>>(emptyList())
    val templates: StateFlow<List<TackleTemplateDto>> = _templates.asStateFlow()

    private val _error = MutableStateFlow<AppError?>(null)
    val error: StateFlow<AppError?> = _error.asStateFlow()

    init {
        loadTemplates()
    }

    private fun loadTemplates() {
        viewModelScope.launch {
            when (val result = repository.tackleTemplates()) {
                is AppResult.Ok -> _templates.value = result.value
                is AppResult.Err -> Unit // templates are a nicety, not a failure
            }
        }
    }

    fun applyTemplates(keys: List<String>) {
        viewModelScope.launch {
            (repository.applyTackleTemplates(keys) as? AppResult.Err)?.let {
                _error.value = it.error
            }
        }
    }

    fun add(item: TackleItemCreate) {
        viewModelScope.launch {
            (repository.addTackle(item) as? AppResult.Err)?.let { _error.value = it.error }
        }
    }

    fun update(id: String, item: TackleItemCreate) {
        viewModelScope.launch {
            (repository.updateTackle(id, item) as? AppResult.Err)?.let { _error.value = it.error }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            (repository.deleteTackle(id) as? AppResult.Err)?.let { _error.value = it.error }
        }
    }

    fun dismissError() {
        _error.value = null
    }
}
