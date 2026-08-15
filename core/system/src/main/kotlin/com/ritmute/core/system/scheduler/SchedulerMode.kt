package com.ritmute.core.system.scheduler

/**
 * How the next wake-up was actually scheduled — the app's single most important health
 * indicator, and the thing the diagnostics screen leads with.
 *
 * Three levels, in descending order of reliability. Note what is **not** here:
 * `setWindow()`. The original spec used it as the degraded mode with a five-minute window;
 * it turned out that for `targetSdk ≥ 31` the real minimum window is ten minutes, and,
 * fatally, that `setWindow` **does not pierce Doze** — it waits for the next maintenance
 * window, which in the small hours can be hours away. The degraded mode would therefore
 * have failed precisely in the flagship use case, "silence from 23:00 to 07:00".
 * See docs/02, decision D-C3.
 */
enum class SchedulerMode {

    /**
     * `setAlarmClock`. Exempt from Doze and from App Standby bucket quotas — the strongest
     * guarantee the platform offers a third-party app.
     *
     * Opt-in only, from the diagnostics screen, because the system shows a permanent alarm
     * icon in the status bar and may surface the app in the alarm shortcut. Users who need
     * the reliability accept it knowingly; users who do not are never surprised by it.
     */
    ALARM_CLOCK,

    /**
     * `setExactAndAllowWhileIdle`. Pierces Doze, honours the exact-alarm permission, and is
     * limited to roughly one firing every nine minutes per app — which is why the domain
     * coalesces transitions closer than that.
     */
    EXACT,

    /**
     * `setAndAllowWhileIdle`. Inexact but still pierces Doze, and needs no special
     * permission. This is the degraded mode: RNF-01 promises only best effort here, with a
     * typical deviation under fifteen minutes and no guarantee.
     *
     * Volume ramps are disabled in this mode: without an exact alarm there is no exemption
     * to start a foreground service from the background, so a ramp could not outlive the
     * receiver's ten seconds.
     */
    INEXACT,
    ;

    /** Whether a transition scheduled in this mode may be followed by a gradual ramp. */
    val allowsRamp: Boolean get() = this != INEXACT
}
