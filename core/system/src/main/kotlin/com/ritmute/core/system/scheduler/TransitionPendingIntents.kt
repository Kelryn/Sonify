package com.ritmute.core.system.scheduler

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * The one and only `PendingIntent` the app schedules, built the same way every time.
 *
 * ## Why there are no extras
 *
 * The alarm carries **no information about which transition to run**. It says one thing:
 * "re-evaluate now". The desired state is a pure function of the instant, so a missed,
 * duplicated, early or late alarm is harmless, and there is nothing to replay.
 *
 * That is not merely a simplification, it is what makes the single-alarm strategy safe.
 * And it has a very practical corollary: `PendingIntent` equality **ignores extras**, so an
 * extra placed here would not create a new intent — it would silently keep the value from
 * the first time the alarm was ever scheduled, which is a classic source of stale data.
 * See docs/02, section 5.2.
 *
 * ## Why the action, and not a class reference
 *
 * The receiver lives in `:app`; this module must not know its class name. A custom action
 * plus `setPackage()` makes the broadcast explicit enough to be delivered to a
 * manifest-declared receiver on API 26+, where implicit broadcasts are otherwise blocked.
 */
object TransitionPendingIntents {

    /**
     * A single constant request code, for every alarm, for the life of the app.
     *
     * Two alarms with the same action and the same request code are the *same*
     * `PendingIntent`, so `FLAG_UPDATE_CURRENT` replaces the previous one instead of
     * accumulating. Deriving the request code from a timestamp or a profile id — the
     * obvious-looking alternative — leaks one pending alarm per transition and makes
     * cancellation impossible, because there is no API to enumerate pending alarms.
     */
    const val REQUEST_CODE = 0x50524F46 // "PROF"

    /** Action `:app` must declare on its (non-exported) transition receiver. */
    const val ACTION_RECONCILE = "com.ritmute.action.RECONCILE"

    /**
     * @param noCreate when true, returns `null` unless the alarm already exists. This is
     *   the only way to answer "is an alarm actually pending?", which the diagnostics
     *   screen needs in order to detect a force-stop that wiped every pending intent.
     */
    fun reconcile(context: Context, noCreate: Boolean = false): PendingIntent? {
        val intent = Intent(ACTION_RECONCILE).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        if (noCreate) flags = flags or PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            intent,
            flags,
        )
    }

    /**
     * Where the system sends the user when they tap the alarm entry, in `ALARM_CLOCK` mode.
     *
     * Resolved from the launcher activity so that this module stays ignorant of `:app`'s
     * class names. `AlarmClockInfo` accepts a null show-intent, so a missing launcher is
     * survivable rather than fatal.
     */
    fun showApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: return null
        return PendingIntent.getActivity(
            context.applicationContext,
            REQUEST_CODE,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
