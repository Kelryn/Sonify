package com.sonoritmo.core.system.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import com.sonoritmo.core.system.R
import com.sonoritmo.core.system.scheduler.SchedulerCoordinator
import com.sonoritmo.core.system.scheduler.Trigger
import com.sonoritmo.core.system.scheduler.WatchdogWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * An **ephemeral** foreground service that performs one reconciliation and stops.
 *
 * ## Why a foreground service at all
 *
 * Two things do not fit in the roughly ten seconds a `BroadcastReceiver` is given: a volume
 * ramp of up to fifteen seconds, and the read-modify-write of the database that follows it.
 * And from Android 17, volume changes made from the background are ignored in silence
 * (risk N6) — a foreground service is what makes the write count.
 *
 * ## Why it is not permanent
 *
 * RNF-02 forbids a permanent foreground service: it is the single biggest reason users
 * uninstall apps in this category, and it is not needed. The alarm wakes the app, the app
 * works for a few seconds, the app disappears. Total wakelock budget under five minutes a
 * day, verifiable in Android Vitals.
 *
 * ## Why `specialUse`
 *
 * None of the predefined foreground service types describes "apply the user's sound profile
 * for a couple of seconds". `specialUse` with an honest subtype is the type Google's own
 * guidance points at for exactly this case, and claiming, say, `mediaPlayback` would be a
 * policy violation.
 *
 * ## The notification the user will never see
 *
 * The default behaviour, `FOREGROUND_SERVICE_DEFERRED`, delays showing a foreground
 * notification by about ten seconds. Since this service lives for less than that, the
 * notification is created, required by the platform, and never actually displayed.
 * Setting `FOREGROUND_SERVICE_IMMEDIATE` would show it every single time a profile changes,
 * which for a night-time silencing app means waking the user up to tell them it made things
 * quiet.
 */
@AndroidEntryPoint
class ApplyProfileService : Service() {

    @Inject
    lateinit var coordinator: SchedulerCoordinator

    /**
     * `Dispatchers.Default`, not `Main`: nothing here touches a view, and the audio writes
     * are binder calls that must not be queued behind whatever the UI thread is doing at
     * 07:00 while the launcher is starting up.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground()

        val trigger = triggerOf(intent)
        scope.launch {
            try {
                coordinator.reconcile(trigger)
            } finally {
                // The service must die even if the pass was cancelled mid-ramp. `stopSelf`
                // with the startId is the documented way to do this safely when several
                // starts overlap: only the most recent id actually stops the service.
                stopSelf(startId)
            }
        }

        // START_NOT_STICKY: if the system kills us, it must not resurrect us with a null
        // intent later. The next alarm — already scheduled before any audio was touched —
        // is the recovery mechanism, and it will recompute the right answer for *then*
        // rather than replaying a stale instruction.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * API 34: timeout for short services.
     *
     * Called instead of the two-argument variant on Android 14. Must stop the service
     * promptly; an app that ignores the timeout is force-stopped by the platform, which on
     * Android 15+ would also cancel every pending intent it owns.
     */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onTimeout(startId: Int) {
        stopSelf(startId)
    }

    /** API 35+: the same contract, with the offending foreground service type attached. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf(startId)
    }

    private fun promoteToForeground() {
        ensureChannel()
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_sonoritmo)
            .setContentTitle(getString(R.string.core_system_applying_profile))
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            // The typed overload exists from API 29, but `specialUse` does not exist before
            // 34, and passing 0 as the type is rejected. The untyped call is correct here.
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * `IMPORTANCE_LOW`, always. An app whose purpose is silence must not make a sound to
     * announce that it has produced silence.
     */
    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.core_system_channel_transitions),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.core_system_channel_transitions_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun triggerOf(intent: Intent?): Trigger {
        val name = intent?.getStringExtra(EXTRA_TRIGGER) ?: return Trigger.ALARM
        return Trigger.entries.firstOrNull { it.name == name } ?: Trigger.ALARM
    }

    companion object {
        const val CHANNEL_ID = "sonoritmo_transitions"
        private const val NOTIFICATION_ID = 0x50524F46
        private const val EXTRA_TRIGGER = "com.sonoritmo.extra.TRIGGER"

        /**
         * Value :app must declare for
         * `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE`. Play reviewers read this string.
         */
        const val SPECIAL_USE_SUBTYPE =
            "Applies the user's scheduled sound profile and volume ramp, then stops."

        /**
         * Starts the service, or reports that the platform would not allow it.
         *
         * @return false when the start was refused. That is not a bug: from Android 12 an app
         *   may only start a foreground service from the background under an exemption, and
         *   the exemption we rely on is *having fired an exact alarm*. In degraded (inexact)
         *   mode there is none, so the caller must fall back to `WorkManager`.
         */
        fun start(context: Context, trigger: Trigger): Boolean = try {
            val intent = Intent(context, ApplyProfileService::class.java)
                .putExtra(EXTRA_TRIGGER, trigger.name)
            context.startForegroundService(intent)
            true
        } catch (illegalState: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException on API 31+.
            false
        } catch (security: SecurityException) {
            false
        }

        /**
         * The call every entry point in :app should use: foreground service when the platform
         * allows it, `WorkManager` when it does not. Late is acceptable; never is not.
         */
        fun startOrEnqueue(context: Context, trigger: Trigger) {
            if (!start(context, trigger)) {
                WatchdogWorker.enqueueOneShot(context, trigger)
            }
        }
    }
}
