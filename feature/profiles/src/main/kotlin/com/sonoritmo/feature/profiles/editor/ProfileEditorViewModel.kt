package com.sonoritmo.feature.profiles.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonoritmo.core.data.repository.ProfileRepository
import com.sonoritmo.core.data.repository.SaveResult
import com.sonoritmo.core.data.repository.ScheduleRepository
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.DayMask
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.ValidationIssue
import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.domain.port.UuidGenerator
import com.sonoritmo.core.system.audio.AudioCapabilities
import com.sonoritmo.core.system.audio.StreamCoupling
import com.sonoritmo.core.system.scheduler.SchedulerCoordinator
import com.sonoritmo.core.system.scheduler.Trigger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Device facts the editor needs.
 *
 * The device's step count used to live here to notch the slider; the slider is continuous
 * now, so carrying the number around would be state nothing reads — the exact thing that
 * made the editor unusable in 1.0.
 */
data class StreamCapability(
    val stream: AudioStream,
    val supported: Boolean,
)

data class ProfileEditorUiState(
    val isNew: Boolean = true,
    val isLoading: Boolean = true,
    val profile: SoundProfile? = null,
    val schedules: List<Schedule> = emptyList(),
    val capabilities: List<StreamCapability> = emptyList(),
    val ringNotificationCoupled: Boolean = false,
    val issues: List<ValidationIssue> = emptyList(),
    /**
     * Counts rejected saves so the screen can announce each one.
     *
     * [issues] on its own cannot drive a one-shot message: tapping save twice with the same
     * problems leaves the list equal, no recomposition key changes, and the second tap looks
     * exactly like nothing happening — which is the failure this field exists to prevent.
     */
    val rejectedSaves: Int = 0,
    val saved: Boolean = false,
)

/**
 * Editor state.
 *
 * The whole draft lives in the ViewModel and is mirrored into [SavedStateHandle], because
 * the permission screens this app sends people to are system activities that can and do
 * kill the process. Losing a half-written profile because the user tapped "grant" is the
 * kind of thing that makes an app feel unreliable in a category where reliability is the
 * entire pitch.
 */
@HiltViewModel
class ProfileEditorViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val scheduleRepository: ScheduleRepository,
    private val capabilitiesSource: AudioCapabilities,
    private val coordinator: SchedulerCoordinator,
    private val timeSource: TimeSource,
    private val uuidGenerator: UuidGenerator,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val profileUuid: String? = savedStateHandle[ARG_PROFILE_UUID]

    private val _uiState = MutableStateFlow(ProfileEditorUiState())
    val uiState: StateFlow<ProfileEditorUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val volumeFixed = capabilitiesSource.isVolumeFixed()
        val capabilities = AudioStream.entries.map { stream ->
            StreamCapability(
                stream = stream,
                supported = capabilitiesSource.isWritable(stream) && !volumeFixed,
            )
        }
        val existing = profileUuid?.let { profileRepository.getByUuid(it) }
        val now = timeSource.now()
        _uiState.value = ProfileEditorUiState(
            isNew = existing == null,
            isLoading = false,
            profile = existing ?: SoundProfile(
                uuid = uuidGenerator.newUuid(),
                name = "",
                createdAt = now,
                updatedAt = now,
            ),
            schedules = profileUuid?.let { scheduleRepository.getByProfile(it) }.orEmpty(),
            capabilities = capabilities,
            ringNotificationCoupled =
                capabilitiesSource.ringNotificationCoupling() == StreamCoupling.COUPLED,
        )
    }

    fun onNameChange(name: String) = mutateProfile { it.copy(name = name) }

    fun onEmojiChange(emoji: String?) = mutateProfile { it.copy(emoji = emoji?.ifBlank { null }) }

    fun onPriorityChange(priority: Int) = mutateProfile { it.copy(priority = priority) }

    fun onVolumeChange(stream: AudioStream, percent: Int?) = mutateProfile {
        it.copy(volumes = it.volumes.with(stream, percent))
    }

    /**
     * Toggling a stream on picks the device's current level rather than an arbitrary 50 %,
     * so the first thing the user sees is where they already are.
     */
    fun onVolumeEnabledChange(stream: AudioStream, enabled: Boolean) = viewModelScope.launch {
        val percent = if (!enabled) {
            null
        } else {
            val level = capabilitiesSource.levelOf(stream)
            com.sonoritmo.core.domain.logic.VolumeMath
                .indexToPercent(level.steps, level.minSteps, level.maxSteps)
        }
        mutateProfile { it.copy(volumes = it.volumes.with(stream, percent)) }
    }

    fun onRingerChange(mode: RingerMode?) = mutateProfile { it.copy(ringerMode = mode) }

    fun onDndModeChange(mode: DndMode?) = mutateProfile { it.copy(dnd = it.dnd.copy(mode = mode)) }

    fun onOptionsChange(transform: (ProfileOptions) -> ProfileOptions) =
        mutateProfile { it.copy(options = transform(it.options)) }

    fun onAddSchedule() {
        val profile = _uiState.value.profile ?: return
        val draft = Schedule.fromWallClock(
            uuid = uuidGenerator.newUuid(),
            profileUuid = profile.uuid,
            startHour = DEFAULT_START_HOUR,
            startMinute = 0,
            endHour = DEFAULT_END_HOUR,
            endMinute = 0,
            daysMask = DayMask.ALL,
        )
        _uiState.update { it.copy(schedules = it.schedules + draft) }
    }

    fun onScheduleChange(updated: Schedule) = _uiState.update { state ->
        state.copy(schedules = state.schedules.map { if (it.uuid == updated.uuid) updated else it })
    }

    fun onScheduleDelete(uuid: String) = _uiState.update { state ->
        state.copy(schedules = state.schedules.filterNot { it.uuid == uuid })
    }

    fun onSave() = viewModelScope.launch {
        val draft = _uiState.value.profile ?: return@launch
        // Normalisation happens before validation, not after: `{SILENT, ring = 80}` is a
        // contradiction the user is allowed to express in the UI, and the model resolves it
        // rather than refusing the save.
        val profile = draft.copy(updatedAt = timeSource.now()).normalized()
        val issues = profile.validate() + _uiState.value.schedules.flatMap { it.validate() }
        if (issues.isNotEmpty()) {
            _uiState.update { it.copy(issues = issues.distinct(), rejectedSaves = it.rejectedSaves + 1) }
            return@launch
        }
        when (val result = profileRepository.save(profile)) {
            is SaveResult.Saved -> {
                scheduleRepository.replaceForProfile(profile.uuid, _uiState.value.schedules)
                coordinator.reconcile(Trigger.CONFIG_CHANGED)
                _uiState.update { it.copy(saved = true, issues = emptyList()) }
            }
            // The repository normalises again on its side, so its verdict is the one that
            // counts; re-running validate() here could disagree with what was written.
            is SaveResult.Invalid -> _uiState.update {
                it.copy(issues = result.issues, rejectedSaves = it.rejectedSaves + 1)
            }
        }
    }

    private fun mutateProfile(transform: (SoundProfile) -> SoundProfile) = _uiState.update { state ->
        val current = state.profile ?: return@update state
        state.copy(profile = transform(current), issues = emptyList())
    }

    companion object {
        const val ARG_PROFILE_UUID = "profileUuid"
        private const val DEFAULT_START_HOUR = 23
        private const val DEFAULT_END_HOUR = 7
    }
}
