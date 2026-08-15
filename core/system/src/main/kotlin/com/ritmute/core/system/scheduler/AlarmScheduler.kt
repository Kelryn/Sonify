package com.ritmute.core.system.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

interface AlarmScheduler {

    /**
     * Replaces the app's single pending alarm with one at [at].
     *
     * @param preferAlarmClock the user's opt-in for maximum reliability.
     * @return the level that was actually achieved, which may be lower than requested if
     *   the exact-alarm permission is not granted. The caller records it: it is the
     *   difference between the two halves of RNF-01.
     */
    fun schedule(at: Instant, preferAlarmClock: Boolean): SchedulerMode

    fun cancel()

    /**
     * Whether the alarm really exists right now, checked with `FLAG_NO_CREATE`.
     *
     * On Android 15+, a force stop cancels every pending intent the app owns without any
     * notification to the app. This is the only way to find out. See docs/02, risk N3.
     */
    fun isPending(): Boolean

    /** `canScheduleExactAlarms()` on API 31+, and always true below it. */
    fun canScheduleExact(): Boolean
}

@Singleton
class AlarmSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val alarmManager: AlarmManager,
) : AlarmScheduler {

    override fun schedule(at: Instant, preferAlarmClock: Boolean): SchedulerMode {
        val triggerAtMillis = at.toEpochMilli()
        val pendingIntent = TransitionPendingIntents.reconcile(context)
            ?: return SchedulerMode.INEXACT

        val exactAllowed = canScheduleExact()

        // Level 1: setAlarmClock. Exempt from Doze *and* from bucket quotas, at the price
        // of an alarm icon in the status bar. Opt-in, never a default.
        if (preferAlarmClock && exactAllowed) {
            try {
                alarmManager.setAlarmClock(
                    AlarmManager.AlarmClockInfo(triggerAtMillis, TransitionPendingIntents.showApp(context)),
                    pendingIntent,
                )
                return SchedulerMode.ALARM_CLOCK
            } catch (security: SecurityException) {
                // The permission was revoked between the check and the call. Fall through.
            }
        }

        // Level 2: exact and allowed while idle. RTC_WAKEUP, never ELAPSED_REALTIME: the
        // app schedules against wall-clock instants computed by the domain, and elapsed
        // time would drift away from them across a reboot or a clock change.
        if (exactAllowed) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent,
                )
                return SchedulerMode.EXACT
            } catch (security: SecurityException) {
                // Same race as above.
            }
        }

        // Level 3: inexact, but still pierces Doze. Never setWindow(): it does not.
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAtMillis,
            pendingIntent,
        )
        return SchedulerMode.INEXACT
    }

    override fun cancel() {
        val pendingIntent = TransitionPendingIntents.reconcile(context, noCreate = true) ?: return
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    override fun isPending(): Boolean =
        TransitionPendingIntents.reconcile(context, noCreate = true) != null

    override fun canScheduleExact(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            // Before Android 12 exact alarms need no permission at all.
            true
        }
}
