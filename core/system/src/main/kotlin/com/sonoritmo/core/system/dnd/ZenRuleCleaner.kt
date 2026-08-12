package com.sonoritmo.core.system.dnd

import android.os.Build
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Version-safe way to delete a system zen rule we no longer own.
 *
 * [ZenRuleRegistrar] is `@RequiresApi(29)`, so it cannot be injected directly into code
 * that also runs on API 26–28 — and a ViewModel is exactly that kind of code. This wrapper
 * keeps the version check in one place instead of scattering `SDK_INT` guards through the
 * feature modules.
 *
 * Why it exists at all: deleting a profile removes our row, but the `AutomaticZenRule` it
 * registered lives in system settings. Orphaned, it keeps activating, cannot be removed
 * from inside the app, and consumes one of the 100 slots the platform allows per package.
 * See docs/02, amendment E-11 and risk N8.
 */
interface ZenRuleCleaner {
    /** No-op below API 29, where the app never created a rule in the first place. */
    fun removeRule(ruleId: String): RuleStatus

    /** Removes every rule we own that no live profile claims. Returns how many went. */
    fun removeOrphans(knownRuleIds: Set<String>): Int
}

@Singleton
class ZenRuleCleanerImpl @Inject constructor(
    private val registrar: Provider<ZenRuleRegistrar>,
) : ZenRuleCleaner {

    override fun removeRule(ruleId: String): RuleStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registrar.get().remove(ruleId)
        } else {
            RuleStatus.NotNeeded
        }

    override fun removeOrphans(knownRuleIds: Set<String>): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            registrar.get().removeOrphans(knownRuleIds)
        } else {
            0
        }
}
