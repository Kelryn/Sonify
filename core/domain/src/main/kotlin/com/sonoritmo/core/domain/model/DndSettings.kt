package com.sonoritmo.core.domain.model

/**
 * What a profile wants from Do Not Disturb.
 *
 * ## Why [RELEASE] and not `OFF`
 *
 * From Android 15, an app targeting API 35+ **cannot change the global DND state**.
 * `setInterruptionFilter` / `setNotificationPolicy` are converted by the platform into
 * an *implicit* `AutomaticZenRule`, and the effective device policy is computed with a
 * "most restrictive wins" scheme. `INTERRUPTION_FILTER_ALL` therefore only deactivates
 * **our own** rule — it cannot switch off DND that the user, Bedtime or Driving mode
 * turned on.
 *
 * Naming the member `OFF` would promise something the platform does not allow.
 * [RELEASE] says what actually happens: this profile releases its own rule.
 *
 * See docs/02, decision D-C1.
 */
enum class DndMode {
    /** Deactivate this profile's zen rule. Does *not* guarantee DND ends. */
    RELEASE,
    PRIORITY,
    ALARMS_ONLY,
    TOTAL_SILENCE,
}

/**
 * Who may break through for calls.
 *
 * `NotificationManager.Policy` only accepts these buckets. There is **no API** to pass a
 * list of specific contacts, and reading contacts would require `READ_CONTACTS`, which
 * contradicts the app's zero-unnecessary-permissions promise. The product solves the
 * "these three people" case by sending the user to star those contacts in the system
 * address book. See docs/02, amendment E-13.
 */
enum class CallPolicy { NONE, STARRED, CONTACTS, ANY }

enum class MessagePolicy { NONE, STARRED, CONTACTS, ANY }

/** Requires API 30+; ignored below it. */
enum class ConversationPolicy { NONE, IMPORTANT, ANYONE }

data class DndSettings(
    val mode: DndMode? = null,
    val allowCalls: CallPolicy = CallPolicy.NONE,
    val allowRepeatCallers: Boolean = true,
    val allowMessages: MessagePolicy = MessagePolicy.NONE,
    val allowConversations: ConversationPolicy = ConversationPolicy.NONE,
    val allowAlarms: Boolean = true,
    val allowMedia: Boolean = true,
    val allowReminders: Boolean = false,
    val allowEvents: Boolean = false,
    /** Bitmask of `NotificationManager.Policy.SUPPRESSED_EFFECT_*`. 0 = suppress nothing. */
    val suppressedVisualEffects: Int = 0,
) {
    /**
     * True when this profile needs a system zen rule at all.
     *
     * Rules are created lazily: `AutomaticZenRule` is capped at 100 per package, and
     * most profiles only touch volumes. See docs/02, amendment E-17.
     */
    val requiresZenRule: Boolean get() = mode != null && mode != DndMode.RELEASE

    companion object {
        val UNCHANGED = DndSettings()
    }
}
