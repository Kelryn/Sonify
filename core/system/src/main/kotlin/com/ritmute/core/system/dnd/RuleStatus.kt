package com.ritmute.core.system.dnd

import com.ritmute.core.domain.model.LogReason
import com.ritmute.core.system.audio.RefusalReason

/**
 * The outcome of a Do Not Disturb operation.
 *
 * Same contract as `AudioOpResult`: expected platform refusals are values, not exceptions.
 *
 * The vocabulary is deliberately about **our own rule**, never about "DND". From
 * Android 15 an app cannot switch Do Not Disturb off — `INTERRUPTION_FILTER_ALL` only
 * deactivates the caller's own `AutomaticZenRule`, and the device's effective policy is the
 * most restrictive of everything active. A member called `Off` would be a lie the UI would
 * then repeat to the user. See docs/02, decision D-C1.
 */
sealed interface RuleStatus {

    /**
     * Our rule exists and is currently asserted.
     *
     * @param persistable false on the API 26–28 compatibility route, where the platform
     *   has no rule to give an id for and [ruleId] is a local marker. Persisting that
     *   marker would leave a meaningless `zenRuleId` in the database that would then travel
     *   in the export JSON to a modern device. See docs/02, amendment E-11.
     */
    data class Active(val ruleId: String, val persistable: Boolean = true) : RuleStatus

    /**
     * Our rule is no longer asserted. Do Not Disturb may perfectly well still be on,
     * because somebody else — the user, Bedtime, Driving mode — is asking for it.
     */
    data class Released(val ruleId: String?) : RuleStatus

    /** The profile does not care about Do Not Disturb, so nothing was touched. */
    data object NotNeeded : RuleStatus

    data class Refused(val reason: RefusalReason) : RuleStatus

    /** Rule id to persist against the profile, when the platform assigned or kept one. */
    val ruleIdOrNull: String?
        get() = when (this) {
            is Active -> ruleId.takeIf { persistable }
            is Released -> ruleId
            else -> null
        }

    val logReason: LogReason?
        get() = when (this) {
            is Refused -> reason.logReason
            else -> null
        }
}
