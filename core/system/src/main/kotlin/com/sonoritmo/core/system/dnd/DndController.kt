package com.sonoritmo.core.system.dnd

import android.app.NotificationManager
import android.os.Build
import androidx.annotation.RequiresApi
import com.sonoritmo.core.domain.model.CallPolicy
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.DndSettings
import com.sonoritmo.core.domain.model.MessagePolicy
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.system.audio.RefusalReason
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the device is not doing what this app asked for — the raw material of differentiator
 * D6 ("tell the user the truth about their phone").
 */
data class DndOverride(
    /** False below API 30, where `getConsolidatedNotificationPolicy` does not exist. */
    val comparisonSupported: Boolean,
    /** Something stricter than us is in force. */
    val overridden: Boolean,
    /** What the device is actually enforcing right now. */
    val effectiveFilter: Int,
    /** What our profile asked for, or `INTERRUPTION_FILTER_UNKNOWN` when we ask nothing. */
    val requestedFilter: Int,
    /** Priority categories we allow that the effective policy strips. */
    val strippedCategories: Int,
) {
    companion object {
        val NONE = DndOverride(
            comparisonSupported = false,
            overridden = false,
            effectiveFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            requestedFilter = NotificationManager.INTERRUPTION_FILTER_UNKNOWN,
            strippedCategories = 0,
        )
    }
}

/**
 * The single entry point for Do Not Disturb, routed **by API level, never by preference**.
 *
 * `DndSettings.useSystemMode` was removed from the model for exactly this reason: on
 * API 29+ an `AutomaticZenRule` is the only route that works, and on API 26–28 it is
 * `setInterruptionFilter`, which *is* global there. Letting the user choose would only let
 * them choose the broken option. See docs/02, decision D-C1.
 */
interface DndController {

    fun hasPolicyAccess(): Boolean

    /** Asserts this profile's DND intent. `mode == null` means "do not touch". */
    fun apply(profile: SoundProfile): RuleStatus

    /**
     * Releases our own claim on Do Not Disturb.
     *
     * @param profile the profile whose rule should be released; `null` releases everything
     *   we own, which is what a dangling "applied profile" reference needs.
     */
    fun release(profile: SoundProfile?): RuleStatus

    /** Whether a stricter rule (user, Bedtime, Driving) is currently beating ours. */
    fun overrideStatus(profile: SoundProfile?): DndOverride

    fun sweepOrphanRules(knownRuleIds: Set<String>): Int

    fun ownedRuleCount(): Int

    fun currentInterruptionFilter(): Int
}

@Singleton
class DndControllerImpl @Inject constructor(
    private val notificationManager: NotificationManager,
    private val zenRuleRegistrar: ZenRuleRegistrar,
) : DndController {

    private val usesZenRules: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    override fun hasPolicyAccess(): Boolean = notificationManager.isNotificationPolicyAccessGranted

    override fun apply(profile: SoundProfile): RuleStatus {
        val mode = profile.dnd.mode ?: return RuleStatus.NotNeeded
        if (!hasPolicyAccess()) return RuleStatus.Refused(RefusalReason.NO_NOTIFICATION_POLICY_ACCESS)
        if (mode == DndMode.RELEASE) return release(profile)

        return if (usesZenRules) {
            zenRuleRegistrar.activate(profile)
        } else {
            applyLegacy(profile.dnd, mode)
        }
    }

    override fun release(profile: SoundProfile?): RuleStatus {
        if (!hasPolicyAccess()) return RuleStatus.Refused(RefusalReason.NO_NOTIFICATION_POLICY_ACCESS)

        if (!usesZenRules) return releaseLegacy()

        val ruleId = profile?.zenRuleId
        return if (ruleId != null) {
            zenRuleRegistrar.deactivate(ruleId)
        } else {
            // No id to work with: the profile was deleted, or it never had a rule. Retract
            // everything we own — they are all ours, so this cannot affect anyone else.
            zenRuleRegistrar.deactivateAll()
            RuleStatus.Released(null)
        }
    }

    override fun overrideStatus(profile: SoundProfile?): DndOverride {
        val requested = profile?.dnd?.mode
            ?.takeIf { it != DndMode.RELEASE }
            ?.let { ZenFilters.filterOf(it) }
            ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        val effective = currentInterruptionFilter()

        val filterOverridden = requested != NotificationManager.INTERRUPTION_FILTER_UNKNOWN &&
            ZenFilters.restrictiveness(effective) > ZenFilters.restrictiveness(requested)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // No consolidated policy to compare against: report only what the filter says.
            return DndOverride(
                comparisonSupported = false,
                overridden = filterOverridden,
                effectiveFilter = effective,
                requestedFilter = requested,
                strippedCategories = 0,
            )
        }
        return consolidatedOverride(requested, effective, filterOverridden)
    }

    /**
     * Compares our *intent* with the device's *effective* policy.
     *
     * `getNotificationPolicy()` returns what this app asked for; `getConsolidatedNotificationPolicy()`
     * returns what the device is actually enforcing once every active rule has been merged
     * with "most restrictive wins". The difference between the two is the honest answer to
     * "why is my phone still silent?", and it is the whole reason D-C1 turned a platform
     * limitation into a product feature.
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun consolidatedOverride(
        requested: Int,
        effective: Int,
        filterOverridden: Boolean,
    ): DndOverride = try {
        val ours = notificationManager.notificationPolicy
        val consolidated = notificationManager.consolidatedNotificationPolicy
        val stripped = ours.priorityCategories and consolidated.priorityCategories.inv()
        DndOverride(
            comparisonSupported = true,
            overridden = filterOverridden || stripped != 0,
            effectiveFilter = effective,
            requestedFilter = requested,
            strippedCategories = stripped,
        )
    } catch (security: SecurityException) {
        DndOverride(
            comparisonSupported = false,
            overridden = filterOverridden,
            effectiveFilter = effective,
            requestedFilter = requested,
            strippedCategories = 0,
        )
    }

    override fun sweepOrphanRules(knownRuleIds: Set<String>): Int =
        if (usesZenRules && hasPolicyAccess()) zenRuleRegistrar.sweepOrphans(knownRuleIds) else 0

    override fun ownedRuleCount(): Int =
        if (usesZenRules && hasPolicyAccess()) zenRuleRegistrar.ruleCount() else 0

    override fun currentInterruptionFilter(): Int = try {
        notificationManager.currentInterruptionFilter
    } catch (security: SecurityException) {
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    // ─── API 26–28 compatibility branch ──────────────────────────────────────────
    //
    // Here, and only here, setInterruptionFilter really does change the global state, and
    // there is no AutomaticZenRule route worth taking (the API exists from 23 but the
    // per-rule ZenPolicy that makes it useful only arrives with 29). This branch is
    // deliberately small: it will be dead code on the vast majority of installs.

    private fun applyLegacy(settings: DndSettings, mode: DndMode): RuleStatus = try {
        notificationManager.notificationPolicy = legacyPolicy(settings)
        notificationManager.setInterruptionFilter(ZenFilters.filterOf(mode))
        // No rule id exists on this route, so the marker is explicitly not persistable:
        // the profile's zenRuleId stays null, which is what a later export to a modern
        // device expects.
        RuleStatus.Active(LEGACY_RULE_ID, persistable = false)
    } catch (security: SecurityException) {
        RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
    }

    private fun releaseLegacy(): RuleStatus = try {
        notificationManager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
        RuleStatus.Released(null)
    } catch (security: SecurityException) {
        RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
    }

    private fun legacyPolicy(settings: DndSettings): NotificationManager.Policy {
        var categories = 0
        if (settings.allowCalls != CallPolicy.NONE) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_CALLS
        }
        if (settings.allowRepeatCallers) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS
        }
        if (settings.allowMessages != MessagePolicy.NONE) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES
        }
        if (settings.allowAlarms) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS
        }
        if (settings.allowReminders) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS
        }
        if (settings.allowEvents) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS
        }
        if (settings.allowMedia && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            categories = categories or NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA
        }

        val callSenders = legacySenders(settings.allowCalls)
        val messageSenders = legacySenders(settings.allowMessages)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            NotificationManager.Policy(categories, callSenders, messageSenders, settings.suppressedVisualEffects)
        } else {
            // The four-argument constructor, and with it any control over visual effects,
            // only exists from API 28.
            NotificationManager.Policy(categories, callSenders, messageSenders)
        }
    }

    private fun legacySenders(policy: CallPolicy): Int = when (policy) {
        CallPolicy.ANY -> NotificationManager.Policy.PRIORITY_SENDERS_ANY
        CallPolicy.CONTACTS -> NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
        // NONE is expressed by leaving the category out entirely; the senders value is then
        // irrelevant, and STARRED is the most conservative thing to put there.
        CallPolicy.STARRED, CallPolicy.NONE -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED
    }

    private fun legacySenders(policy: MessagePolicy): Int = when (policy) {
        MessagePolicy.ANY -> NotificationManager.Policy.PRIORITY_SENDERS_ANY
        MessagePolicy.CONTACTS -> NotificationManager.Policy.PRIORITY_SENDERS_CONTACTS
        MessagePolicy.STARRED, MessagePolicy.NONE -> NotificationManager.Policy.PRIORITY_SENDERS_STARRED
    }

    private companion object {
        /**
         * Marker stored in place of a rule id on the API 26–28 route, where the platform
         * has no rule to give us an id for. It is never written to the database.
         */
        const val LEGACY_RULE_ID = "legacy-interruption-filter"
    }
}
