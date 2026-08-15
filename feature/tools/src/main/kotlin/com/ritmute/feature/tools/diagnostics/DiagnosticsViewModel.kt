package com.ritmute.feature.tools.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritmute.core.data.preferences.UserPreferences
import com.ritmute.core.data.repository.SchedulingWorldRepository
import com.ritmute.core.system.diagnostics.OemAction
import com.ritmute.core.system.diagnostics.OemGuidance
import com.ritmute.core.system.diagnostics.SystemDiagnostics
import com.ritmute.core.system.diagnostics.SystemDiagnosticsReport
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DiagnosticsUiState(
    val report: SystemDiagnosticsReport? = null,
    val vendorActions: List<OemAction> = emptyList(),
    val maxReliability: Boolean = false,
)

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diagnostics: SystemDiagnostics,
    private val worldRepository: SchedulingWorldRepository,
    private val preferences: UserPreferences,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiagnosticsUiState())
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() = viewModelScope.launch {
        val world = worldRepository.load()
        val report = diagnostics.report(world.profile(world.automation.appliedProfileUuid))
        _uiState.update {
            it.copy(
                report = report,
                vendorActions = OemGuidance.resolvableActions(context, report.vendor),
                maxReliability = preferences.settings.first().maxReliabilityMode,
            )
        }
    }

    fun setMaxReliability(enabled: Boolean) = viewModelScope.launch {
        preferences.setMaxReliabilityMode(enabled)
        _uiState.update { it.copy(maxReliability = enabled) }
    }

    fun notificationPolicyIntent(): Intent =
        Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)

    /**
     * Returns `null` below API 31, where the permission does not exist and exact alarms are
     * always available. Rendering a dead button there would be worse than rendering none.
     */
    fun exactAlarmIntent(): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null))
        } else {
            null
        }

    /**
     * Opens the system's battery-optimisation list rather than requesting the exemption
     * directly.
     *
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` is a common cause of Play policy rejections
     * and would have to be declared in the manifest. This route needs no permission at all
     * and lands the user on the same setting. See docs/02, amendment E-16.
     */
    fun batteryOptimisationIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
