package com.sonoritmo.feature.profiles.list

import com.sonoritmo.core.domain.model.ActivationSource
import com.sonoritmo.core.domain.model.ProfileTemplate
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import java.time.Instant

/** One row of the list, already resolved so the composable does no thinking. */
data class ProfileRow(
    val profile: SoundProfile,
    val schedules: List<Schedule>,
    val isActive: Boolean,
)

/**
 * What the banner announces.
 *
 * Modelled as a sealed hierarchy rather than a bag of nullable fields so the screen cannot
 * render "active" and "paused" at the same time, and so adding a state later forces every
 * `when` to be revisited.
 */
sealed interface ActiveStatus {
    data object Loading : ActiveStatus

    data class Running(
        val profile: SoundProfile,
        val source: ActivationSource,
        val schedule: Schedule?,
        val endsAt: Instant?,
    ) : ActiveStatus

    data class Idle(val nextProfile: SoundProfile?, val nextAt: Instant?) : ActiveStatus

    data class Paused(val until: Instant) : ActiveStatus

    /**
     * Something the user must fix before the app can keep its promise — missing Do Not
     * Disturb access, or exact alarms denied. Surfaced on the home screen rather than
     * buried in settings, because a scheduling app that silently cannot schedule is the
     * exact failure mode this project exists to avoid.
     */
    data class Degraded(val missingPolicyAccess: Boolean, val inexactAlarms: Boolean) : ActiveStatus
}

data class ProfilesUiState(
    val isLoading: Boolean = true,
    val rows: List<ProfileRow> = emptyList(),
    val status: ActiveStatus = ActiveStatus.Loading,
    val templates: List<ProfileTemplate> = ProfileTemplate.entries,
    val message: UserMessage? = null,
)

/** A one-shot message, held in state so it survives rotation and is dismissed explicitly. */
data class UserMessage(val kind: Kind, val argument: String? = null) {
    enum class Kind {
        PROFILE_ACTIVATED,
        PROFILE_DEACTIVATED,
        PROFILE_DELETED,
        PROFILE_DUPLICATED,
        PAUSE_STARTED,
        PAUSE_CLEARED,
        SAVE_FAILED,
    }
}

sealed interface ProfilesEvent {
    data class Activate(val uuid: String) : ProfilesEvent
    data class ActivateFor(val uuid: String, val minutes: Int?) : ProfilesEvent
    data object Deactivate : ProfilesEvent
    data class ToggleEnabled(val uuid: String, val enabled: Boolean) : ProfilesEvent
    data class Duplicate(val uuid: String) : ProfilesEvent
    data class Delete(val uuid: String) : ProfilesEvent
    data class CreateFromTemplate(val template: ProfileTemplate) : ProfilesEvent
    data class PauseAll(val minutes: Int?) : ProfilesEvent
    data object ResumeAll : ProfilesEvent
    data object DismissMessage : ProfilesEvent
    data object Refresh : ProfilesEvent
}
