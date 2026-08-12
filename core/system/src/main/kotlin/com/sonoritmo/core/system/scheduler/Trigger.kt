package com.sonoritmo.core.system.scheduler

import com.sonoritmo.core.domain.model.LogReason

/**
 * What woke the reconciler up.
 *
 * A trigger is **provenance, not instruction**. Every one of them runs exactly the same
 * code path and produces exactly the same answer for a given instant; the value is carried
 * only so the activity log can tell the user *why* the app looked at the clock. Any
 * behaviour that varied by trigger would break the level-triggered invariant that makes a
 * lost alarm harmless. See docs/02, section 5.2.
 */
enum class Trigger(val logReason: LogReason?) {

    /** The single pending alarm fired. The expected, healthy case. */
    ALARM(null),

    /** The hourly `WorkManager` watchdog. Repairs drift; never a precision guarantee. */
    WATCHDOG(LogReason.WATCHDOG_REPAIR),

    /** `BOOT_COMPLETED`: the device was unlocked after a restart. */
    BOOT(LogReason.BOOT_RECONCILE),

    /**
     * `LOCKED_BOOT_COMPLETED`: the device restarted and has not been unlocked yet.
     *
     * Without this, a 03:00 reboot on a phone unlocked at 08:00 loses every night-time
     * transition, because `BOOT_COMPLETED` does not arrive until the user unlocks.
     * See docs/02, risk N2.
     */
    LOCKED_BOOT(LogReason.LOCKED_BOOT_RECONCILE),

    /** `ACTION_TIME_CHANGED`: the wall clock moved under us. */
    TIME_CHANGED(LogReason.TIME_CHANGED),

    /** `ACTION_TIMEZONE_CHANGED`: the user travelled. Every local window must be recomputed. */
    TIMEZONE_CHANGED(LogReason.TIMEZONE_CHANGED),

    /** `MY_PACKAGE_REPLACED`: an update cancelled our alarms. */
    APP_UPDATED(LogReason.APP_UPDATED),

    /** `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`, or policy access changing. */
    PERMISSION_CHANGED(LogReason.PERMISSION_GRANTED),

    /**
     * The user touched something: the quick-settings tile, the widget, or the app itself.
     *
     * These replace `ACTION_USER_PRESENT`, which cannot be declared in a manifest at all on
     * API 26+ and would therefore never have been delivered. `TileService.onStartListening`
     * is the closest thing the platform still offers to "the user just unlocked".
     * See docs/02, decision D-C4.
     */
    USER_INTERACTION(null),

    /** Profiles or schedules were edited, so the next transition may have moved. */
    CONFIG_CHANGED(null),

    /** A configuration was imported wholesale. */
    IMPORT(LogReason.IMPORT_APPLIED),
    ;

    /**
     * Whether this trigger is allowed to push the device back onto the active profile when
     * it has drifted.
     *
     * Only system-driven triggers are. If the user has just raised the volume by hand and
     * then opens the app, snapping it back down would be the app arguing with the person
     * using it — and "tap to activate" (RF) means the user is expected to override us.
     * A drift repair is a background correction, not a UI side effect.
     */
    val repairsDrift: Boolean
        get() = this == WATCHDOG || this == ALARM || this == BOOT || this == LOCKED_BOOT

    /**
     * Whether this trigger should sweep zen rules that no profile claims.
     *
     * Only at the moments where orphans can appear: a cold start after profiles were
     * deleted, an update, or a wholesale import that replaced the entire configuration.
     * Sweeping on every pass would mean an extra binder round trip every hour, for ever,
     * to find nothing.
     */
    val sweepsOrphanRules: Boolean
        get() = this == BOOT || this == APP_UPDATED || this == IMPORT
}
