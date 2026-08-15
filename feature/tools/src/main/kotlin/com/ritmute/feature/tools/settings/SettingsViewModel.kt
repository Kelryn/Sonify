package com.ritmute.feature.tools.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ritmute.core.data.backup.BackupExporter
import com.ritmute.core.data.backup.BackupImporter
import com.ritmute.core.data.backup.ImportMode
import com.ritmute.core.data.preferences.ThemeMode
import com.ritmute.core.data.preferences.UserPreferences
import com.ritmute.core.data.repository.ProfileRepository
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.system.scheduler.SchedulerCoordinator
import com.ritmute.core.system.scheduler.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-shot outcome of an import or export, shown once and then cleared. */
sealed interface BackupOutcome {
    data class Exported(val profiles: Int) : BackupOutcome
    data class Imported(val profiles: Int, val corrections: Int) : BackupOutcome
    data object Failed : BackupOutcome
}

data class SettingsUiState(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val defaultProfileUuid: String? = null,
    val profiles: List<SoundProfile> = emptyList(),
    val backupOutcome: BackupOutcome? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferences,
    private val profileRepository: ProfileRepository,
    private val exporter: BackupExporter,
    private val importer: BackupImporter,
    private val coordinator: SchedulerCoordinator,
) : ViewModel() {

    private val outcome = MutableStateFlow<BackupOutcome?>(null)

    val uiState: StateFlow<SettingsUiState> =
        combine(
            preferences.settings,
            profileRepository.observeAll(),
            outcome,
        ) { settings, profiles, backupOutcome ->
            SettingsUiState(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                defaultProfileUuid = settings.defaultProfileUuid,
                profiles = profiles,
                backupOutcome = backupOutcome,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
            initialValue = SettingsUiState(),
        )

    fun setThemeMode(mode: ThemeMode) = viewModelScope.launch { preferences.setThemeMode(mode) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch {
        preferences.setDynamicColor(enabled)
    }

    fun setDefaultProfile(uuid: String?) = viewModelScope.launch {
        preferences.setDefaultProfileUuid(uuid)
        coordinator.reconcile(Trigger.CONFIG_CHANGED)
    }

    fun exportTo(uri: Uri) = viewModelScope.launch {
        val result = runCatching {
            val summary = exporter.export()
            context.contentResolver.openOutputStream(uri)?.use { stream ->
                stream.write(summary.json.toByteArray(Charsets.UTF_8))
            } ?: error("could not open $uri for writing")
            summary.profileCount
        }
        outcome.value = result.fold(
            onSuccess = { BackupOutcome.Exported(it) },
            onFailure = { BackupOutcome.Failed },
        )
    }

    fun importFrom(uri: Uri, mode: ImportMode) = viewModelScope.launch {
        val result = runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("could not open $uri for reading")
            importer.import(raw, mode)
        }
        outcome.value = result.fold(
            onSuccess = { report ->
                if (!report.applied) {
                    BackupOutcome.Failed
                } else {
                    // The windows the current alarm was armed against may no longer exist.
                    // Rearming is mandatory: an import that leaves yesterday's alarm in
                    // place breaks the central promise while reporting success.
                    coordinator.reconcile(Trigger.IMPORT)
                    BackupOutcome.Imported(report.profilesTouched, report.corrections.size)
                }
            },
            onFailure = { BackupOutcome.Failed },
        )
    }

    fun dismissOutcome() {
        outcome.value = null
    }

    private companion object {
        const val STOP_TIMEOUT_MILLIS = 5_000L
    }
}
