package com.sonoritmo.core.system.dnd

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Build
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import androidx.annotation.RequiresApi
import com.sonoritmo.core.domain.model.CallPolicy
import com.sonoritmo.core.domain.model.ConversationPolicy
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.DndSettings
import com.sonoritmo.core.domain.model.MessagePolicy
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.system.audio.RefusalReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Shared mapping from the domain's DND vocabulary to platform interruption filters. */
internal object ZenFilters {

    fun filterOf(mode: DndMode): Int = when (mode) {
        DndMode.RELEASE -> NotificationManager.INTERRUPTION_FILTER_ALL
        DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        DndMode.ALARMS_ONLY -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        DndMode.TOTAL_SILENCE -> NotificationManager.INTERRUPTION_FILTER_NONE
    }

    /**
     * How restrictive a filter is, higher meaning stricter.
     *
     * Written out rather than comparing the constants directly: their numeric values do
     * happen to be ordered this way today, and a future platform value would silently
     * break an app that relied on the coincidence.
     */
    fun restrictiveness(filter: Int): Int = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> 1
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> 2
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> 3
        NotificationManager.INTERRUPTION_FILTER_NONE -> 4
        else -> 0 // INTERRUPTION_FILTER_UNKNOWN, or anything added later.
    }

    /** Scheme of our condition URIs. Also the marker that tells our rules from anyone else's. */
    const val CONDITION_SCHEME = "sonoritmo"
    const val CONDITION_AUTHORITY = "zen"

    fun conditionUri(profileUuid: String): Uri = Uri.Builder()
        .scheme(CONDITION_SCHEME)
        .authority(CONDITION_AUTHORITY)
        .appendPath("profile")
        .appendPath(profileUuid)
        .build()

    fun isOurs(conditionId: Uri?): Boolean =
        conditionId?.scheme == CONDITION_SCHEME && conditionId.authority == CONDITION_AUTHORITY
}

/**
 * Owner of the app's `AutomaticZenRule`s.
 *
 * The whole Do Not Disturb strategy from Android 10 onwards lives behind this interface.
 * The interface itself carries no `@RequiresApi` so that callers can hold it
 * unconditionally; the implementation does, and every entry point is version-guarded by
 * [DndController].
 */
interface ZenRuleRegistrar {

    /** Creates or updates this profile's rule. Returns the id the caller must persist. */
    fun ensureRule(profile: SoundProfile): RuleStatus

    /** Asserts the rule's condition, which is what actually turns the mode on. */
    fun activate(profile: SoundProfile): RuleStatus

    /** Retracts the condition. The rule survives, so its id and the user's edits survive too. */
    fun deactivate(ruleId: String): RuleStatus

    /** Retracts every rule we own. Used when the applied profile reference is dangling. */
    fun deactivateAll(): Int

    fun remove(ruleId: String): RuleStatus

    /**
     * Deletes rules we own that no profile claims any more.
     *
     * Without this, deleting a profile leaves a mode in the system settings that the user
     * can see, cannot explain, and cannot remove from our app — and every orphan eats one
     * of the 100 slots the platform allows per package. See docs/02, amendment E-17 and
     * risk N8.
     *
     * @param knownRuleIds every rule id still referenced by a stored profile.
     * @return how many rules were removed.
     */
    fun sweepOrphans(knownRuleIds: Set<String>): Int

    fun ruleCount(): Int
}

@Singleton
@RequiresApi(Build.VERSION_CODES.Q)
class ZenRuleRegistrarImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
) : ZenRuleRegistrar {

    override fun ensureRule(profile: SoundProfile): RuleStatus {
        if (!notificationManager.isNotificationPolicyAccessGranted) {
            return RuleStatus.Refused(RefusalReason.NO_NOTIFICATION_POLICY_ACCESS)
        }
        val mode = profile.dnd.mode
        if (mode == null || mode == DndMode.RELEASE) return RuleStatus.NotNeeded

        val rule = buildRule(profile, mode)
        val existingId = profile.zenRuleId

        return try {
            if (existingId != null && notificationManager.getAutomaticZenRule(existingId) != null) {
                notificationManager.updateAutomaticZenRule(existingId, rule)
                RuleStatus.Active(existingId)
            } else {
                // Defensive ceiling below the platform's 100, so that a corrupted state can
                // still be repaired instead of hitting a hard wall with no room to work in.
                if (ruleCount() >= MAX_OWNED_RULES) {
                    return RuleStatus.Refused(RefusalReason.ZEN_RULE_LIMIT_REACHED)
                }
                val created: String? = notificationManager.addAutomaticZenRule(rule)
                if (created == null) {
                    RuleStatus.Refused(RefusalReason.SERVICE_UNAVAILABLE)
                } else {
                    RuleStatus.Active(created)
                }
            }
        } catch (security: SecurityException) {
            RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
        }
    }

    override fun activate(profile: SoundProfile): RuleStatus {
        val ensured = ensureRule(profile)
        val ruleId = (ensured as? RuleStatus.Active)?.ruleId ?: return ensured

        return try {
            notificationManager.setAutomaticZenRuleState(
                ruleId,
                Condition(
                    ZenFilters.conditionUri(profile.uuid),
                    profile.name,
                    Condition.STATE_TRUE,
                ),
            )
            RuleStatus.Active(ruleId)
        } catch (security: SecurityException) {
            RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
        }
    }

    override fun deactivate(ruleId: String): RuleStatus = try {
        val rule = notificationManager.getAutomaticZenRule(ruleId)
        if (rule == null) {
            // Already gone — the user deleted the mode from Settings. Nothing to do, and
            // certainly nothing to report as an error.
            RuleStatus.Released(null)
        } else {
            notificationManager.setAutomaticZenRuleState(
                ruleId,
                Condition(rule.conditionId, rule.name, Condition.STATE_FALSE),
            )
            RuleStatus.Released(ruleId)
        }
    } catch (security: SecurityException) {
        RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
    }

    override fun deactivateAll(): Int = ownedRules().count { (id, _) ->
        deactivate(id) is RuleStatus.Released
    }

    override fun remove(ruleId: String): RuleStatus = try {
        notificationManager.removeAutomaticZenRule(ruleId)
        RuleStatus.Released(null)
    } catch (security: SecurityException) {
        RuleStatus.Refused(RefusalReason.SECURITY_EXCEPTION)
    }

    override fun sweepOrphans(knownRuleIds: Set<String>): Int {
        var removed = 0
        ownedRules().forEach { (id, _) ->
            if (id !in knownRuleIds) {
                if (remove(id) is RuleStatus.Released) removed++
            }
        }
        return removed
    }

    override fun ruleCount(): Int = ownedRules().size

    /**
     * The rules that are ours.
     *
     * `getAutomaticZenRules()` already returns only the caller's rules, but we filter by
     * our own condition URI on top of that: the sweep deletes things, and a defensive
     * second key costs one string comparison.
     */
    private fun ownedRules(): Map<String, AutomaticZenRule> = try {
        notificationManager.automaticZenRules
            .filterValues { ZenFilters.isOurs(it.conditionId) }
    } catch (security: SecurityException) {
        emptyMap()
    }

    private fun buildRule(profile: SoundProfile, mode: DndMode): AutomaticZenRule =
        AutomaticZenRule(
            profile.name,
            // owner must be null when a configuration activity is used. Supplying a
            // ConditionProviderService here instead would drag in the special access
            // BIND_CONDITION_PROVIDER_SERVICE, which Play reviews as a sensitive
            // capability and which this app has no need for. See docs/02, amendment E-18.
            null,
            configurationActivity(),
            ZenFilters.conditionUri(profile.uuid),
            buildPolicy(profile.dnd),
            ZenFilters.filterOf(mode),
            true,
        )

    /**
     * Where Settings sends the user when they tap our mode.
     *
     * Resolved from the launcher activity rather than hard-coded, because the activity
     * lives in `:app` and this module must not know its class name. If the app somehow has
     * no launcher activity the rule is still valid — the row is simply not tappable.
     */
    private fun configurationActivity(): ComponentName? =
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.component

    private fun buildPolicy(settings: DndSettings): ZenPolicy {
        val builder = ZenPolicy.Builder()
            .allowCalls(peopleType(settings.allowCalls))
            .allowRepeatCallers(settings.allowRepeatCallers)
            .allowMessages(peopleType(settings.allowMessages))
            .allowAlarms(settings.allowAlarms)
            .allowMedia(settings.allowMedia)
            .allowReminders(settings.allowReminders)
            .allowEvents(settings.allowEvents)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.allowConversations(conversationType(settings.allowConversations))
        }
        // Below API 30 the platform has no notion of conversation senders at all, so the
        // field is simply not expressible; the rest of the policy still applies.

        applyVisualEffects(builder, settings.suppressedVisualEffects)
        return builder.build()
    }

    /**
     * Translates the `NotificationManager.Policy.SUPPRESSED_EFFECT_*` bitmask the domain
     * stores into the positive "show this" vocabulary `ZenPolicy` uses.
     *
     * Only bits that are set are touched: `ZenPolicy` distinguishes "unset" from
     * "explicitly allowed", and forcing every effect to `true` would override whatever the
     * user configured in the system for the other modes.
     */
    private fun applyVisualEffects(builder: ZenPolicy.Builder, suppressed: Int) {
        fun suppressedHas(bit: Int): Boolean = suppressed and bit != 0

        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_FULL_SCREEN_INTENT)) {
            builder.showFullScreenIntent(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_LIGHTS)) {
            builder.showLights(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK)) {
            builder.showPeeking(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_STATUS_BAR)) {
            builder.showStatusBarIcons(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_BADGE)) {
            builder.showBadges(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_AMBIENT)) {
            builder.showInAmbientDisplay(false)
        }
        if (suppressedHas(NotificationManager.Policy.SUPPRESSED_EFFECT_NOTIFICATION_LIST)) {
            builder.showNotificationList(false)
        }
    }

    private fun peopleType(policy: CallPolicy): Int = when (policy) {
        CallPolicy.NONE -> ZenPolicy.PEOPLE_TYPE_NONE
        CallPolicy.STARRED -> ZenPolicy.PEOPLE_TYPE_STARRED
        CallPolicy.CONTACTS -> ZenPolicy.PEOPLE_TYPE_CONTACTS
        CallPolicy.ANY -> ZenPolicy.PEOPLE_TYPE_ANYONE
    }

    private fun peopleType(policy: MessagePolicy): Int = when (policy) {
        MessagePolicy.NONE -> ZenPolicy.PEOPLE_TYPE_NONE
        MessagePolicy.STARRED -> ZenPolicy.PEOPLE_TYPE_STARRED
        MessagePolicy.CONTACTS -> ZenPolicy.PEOPLE_TYPE_CONTACTS
        MessagePolicy.ANY -> ZenPolicy.PEOPLE_TYPE_ANYONE
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun conversationType(policy: ConversationPolicy): Int = when (policy) {
        ConversationPolicy.NONE -> ZenPolicy.CONVERSATION_SENDERS_NONE
        ConversationPolicy.IMPORTANT -> ZenPolicy.CONVERSATION_SENDERS_IMPORTANT
        ConversationPolicy.ANYONE -> ZenPolicy.CONVERSATION_SENDERS_ANYONE
    }

    private companion object {
        /**
         * The platform caps a package at 100 `AutomaticZenRule`s. Stopping at 90 leaves
         * headroom to repair a broken state instead of failing at the wall.
         */
        const val MAX_OWNED_RULES = 90
    }
}
