package com.sonoritmo.core.domain.model

import java.time.Instant

/**
 * Everything about automation that is not a profile or a schedule, and that must
 * survive process death and reboot.
 *
 * This aggregate was missing entirely from the first draft of the spec even though the
 * conflict algorithm consults it in its first two steps. It lives in the database, not
 * in preferences, because [manualProfileUuid] and [appliedProfileUuid] reference
 * profiles: deleting a profile has to null them out inside the same transaction, and a
 * key-value store cannot do that. General rule for this project: anything that takes
 * part in conflict resolution lives in the database. See docs/02, amendment E-08.
 */
data class AutomationState(
    /** All rules suspended until this instant. `null` = not paused. */
    val globalPauseUntil: Instant? = null,

    /** Profile the user activated by hand. Beats anything scheduled. */
    val manualProfileUuid: String? = null,
    /** When the manual activation expires. `null` with a profile set = indefinite. */
    val manualUntil: Instant? = null,
    val manualActivatedAt: Instant? = null,

    /** What is actually applied right now, so we can detect drift. */
    val appliedProfileUuid: String? = null,
    val appliedScheduleUuid: String? = null,
    val appliedAt: Instant? = null,

    /** Diagnostics (RF-33). */
    val nextTransitionAt: Instant? = null,
    val lastReconciliationAt: Instant? = null,
    /** How many times the watchdog found the state wrong and fixed it. Health KPI. */
    val repairCount: Int = 0,
) {
    fun isPausedAt(now: Instant): Boolean {
        val until = globalPauseUntil ?: return false
        return now < until
    }

    fun manualActiveAt(now: Instant): Boolean {
        if (manualProfileUuid == null) return false
        val until = manualUntil ?: return true
        return now < until
    }

    companion object {
        val EMPTY = AutomationState()
    }
}
