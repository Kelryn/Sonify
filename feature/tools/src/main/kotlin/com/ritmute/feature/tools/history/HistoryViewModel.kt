package com.ritmute.feature.tools.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritmute.core.data.repository.ActivityLogRepository
import com.ritmute.core.data.repository.LogFilter
import com.ritmute.core.domain.model.ActivityLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class HistoryUiState(
    val isLoading: Boolean = true,
    val entries: List<ActivityLogEntry> = emptyList(),
    val onlyFailures: Boolean = false,
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val repository: ActivityLogRepository,
) : ViewModel() {

    private val onlyFailures = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<HistoryUiState> =
        onlyFailures
            .flatMapLatest { failuresOnly ->
                repository.observeFiltered(LogFilter(onlyFailures = failuresOnly))
                    .map { entries -> failuresOnly to entries }
            }
            .map { (failuresOnly, entries) ->
                HistoryUiState(isLoading = false, entries = entries, onlyFailures = failuresOnly)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
                initialValue = HistoryUiState(),
            )

    fun setOnlyFailures(value: Boolean) {
        onlyFailures.value = value
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
