package com.ritmute.core.domain.model

import java.time.ZoneId

/**
 * An immutable snapshot of everything that can influence a scheduling decision.
 *
 * This is the **only** input to the pure functions in `logic/`. Nothing in that package
 * reads a clock, a time zone, a database or a system service; it all arrives here. That
 * is what makes the core of the app verifiable without an Android toolchain, and what
 * makes every decision reproducible in a unit test.
 */
data class SchedulingWorld(
    val zoneId: ZoneId,
    val profilesByUuid: Map<String, SoundProfile>,
    val schedules: List<Schedule>,
    val automation: AutomationState = AutomationState.EMPTY,
    /**
     * Applied when a window ends and the ending profile does not ask to restore the
     * baseline. `null` falls back to restoring the baseline.
     */
    val defaultProfileUuid: String? = null,
    val baseline: AudioSnapshot? = null,
) {
    /** Schedules that are enabled *and* whose profile exists and is enabled. */
    val activeSchedules: List<Schedule> by lazy {
        schedules.filter { schedule ->
            schedule.enabled && profilesByUuid[schedule.profileUuid]?.enabled == true
        }
    }

    fun profile(uuid: String?): SoundProfile? = uuid?.let { profilesByUuid[it] }

    companion object {
        fun of(
            zoneId: ZoneId,
            profiles: List<SoundProfile>,
            schedules: List<Schedule>,
            automation: AutomationState = AutomationState.EMPTY,
            defaultProfileUuid: String? = null,
            baseline: AudioSnapshot? = null,
        ): SchedulingWorld = SchedulingWorld(
            zoneId = zoneId,
            profilesByUuid = profiles.associateBy { it.uuid },
            schedules = schedules,
            automation = automation,
            defaultProfileUuid = defaultProfileUuid,
            baseline = baseline,
        )
    }
}
