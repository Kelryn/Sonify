package com.sonoritmo.core.domain.model

/** Where an active profile came from. */
enum class ActivationSource { SCHEDULE, MANUAL }

/**
 * What *should* be true right now. A total function of ([SchedulingWorld], instant) —
 * never of how we got here.
 *
 * This is the property that makes the whole single-alarm design safe: the alarm carries
 * no instruction, only the signal "re-evaluate now". A missed, duplicated, early or late
 * alarm is harmless, because the answer is recomputed from scratch every time.
 * See docs/02, section 5.2.
 */
sealed interface DesiredState {

    /** All automation suspended by the user. Treated like [Idle] when applying. */
    data class Paused(val untilEpochMillis: Long) : DesiredState

    /** Nothing should be enforced: restore the baseline or apply the default profile. */
    data object Idle : DesiredState

    data class Active(
        val profile: SoundProfile,
        val source: ActivationSource,
        val scheduleUuid: String?,
    ) : DesiredState

    val activeProfileUuid: String?
        get() = (this as? Active)?.profile?.uuid

    val isActive: Boolean get() = this is Active
}
