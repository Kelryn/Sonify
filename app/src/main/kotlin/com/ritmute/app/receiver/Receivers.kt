package com.ritmute.app.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ritmute.core.system.scheduler.SchedulerCoordinator
import com.ritmute.core.system.scheduler.Trigger
import com.ritmute.core.system.scheduler.WatchdogWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Shared plumbing for every receiver in the app.
 *
 * A `BroadcastReceiver` has roughly ten seconds of life and loses its wake lock the moment
 * `onReceive` returns. `goAsync()` buys the time to finish a reconciliation pass; the
 * timeout is the safety valve, and when it fires the work is handed to an expedited worker
 * rather than abandoned. Dropping it would mean the phone silently keeps the previous
 * profile, which is the failure this whole project exists to prevent.
 */
abstract class ReconcilingReceiver : BroadcastReceiver() {

    @Inject lateinit var coordinator: SchedulerCoordinator

    protected abstract fun triggerFor(intent: Intent): Trigger?

    override fun onReceive(context: Context, intent: Intent) {
        val trigger = triggerFor(intent) ?: return
        val pendingResult = goAsync()
        val appContext = context.applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val finished = withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) {
                    coordinator.reconcile(trigger)
                }
                if (finished == null) {
                    WatchdogWorker.enqueueOneShot(appContext, trigger)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        /** Comfortably inside the ~10 s a receiver gets, with room to finish cleanly. */
        const val RECEIVER_BUDGET_MILLIS = 8_000L
    }
}

/**
 * The single pending alarm fired.
 *
 * The intent carries **no** information about which transition to run. That is the
 * level-triggered invariant: the alarm is a "re-evaluate now" signal and nothing else, so a
 * late, early, duplicated or lost alarm cannot corrupt the outcome. Putting a timestamp in
 * the extras would also be pointless — `PendingIntent` equality ignores extras, which is a
 * classic source of stale data. See docs/02, section 5.2.
 */
@AndroidEntryPoint
class TransitionAlarmReceiver : ReconcilingReceiver() {
    override fun triggerFor(intent: Intent): Trigger = Trigger.ALARM
}

/**
 * Restart recovery.
 *
 * Listens for `LOCKED_BOOT_COMPLETED` as well as `BOOT_COMPLETED`, and is declared
 * `directBootAware`. On a file-encrypted device `BOOT_COMPLETED` does not arrive until the
 * user unlocks: a 03:00 reboot on a phone unlocked at 08:00 would otherwise lose every
 * night-time transition — which is precisely the flagship use case. See docs/02, risk N2.
 *
 * The OEM-specific quick-boot actions are harmless where they are not sent and are the only
 * signal on a few devices that never emit the standard one.
 */
@AndroidEntryPoint
class BootReceiver : ReconcilingReceiver() {
    override fun triggerFor(intent: Intent): Trigger = when (intent.action) {
        Intent.ACTION_LOCKED_BOOT_COMPLETED -> Trigger.LOCKED_BOOT
        else -> Trigger.BOOT
    }
}

/**
 * The wall clock or the time zone moved.
 *
 * Both actions are on the allow-list of implicit broadcasts a manifest receiver may still
 * declare on API 26+, so this one genuinely works. Every local window has to be recomputed:
 * caching a zone is the classic bug that survives a flight and then misfires for a week.
 */
@AndroidEntryPoint
class TimeChangeReceiver : ReconcilingReceiver() {
    override fun triggerFor(intent: Intent): Trigger = when (intent.action) {
        Intent.ACTION_TIMEZONE_CHANGED -> Trigger.TIMEZONE_CHANGED
        else -> Trigger.TIME_CHANGED
    }
}

/** An update cancelled our alarms; re-arm immediately rather than at the next app launch. */
@AndroidEntryPoint
class PackageReplacedReceiver : ReconcilingReceiver() {
    override fun triggerFor(intent: Intent): Trigger = Trigger.APP_UPDATED
}

/**
 * Exact-alarm permission was granted.
 *
 * Only the *grant* is broadcast. Revoking it puts the app into the stopped state and
 * cancels its alarms, so there is no code path that can react to a revocation — which is
 * why the onboarding and the diagnostics screen say so in advance instead.
 */
@AndroidEntryPoint
class ExactAlarmPermissionReceiver : ReconcilingReceiver() {
    override fun triggerFor(intent: Intent): Trigger? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            Trigger.PERMISSION_CHANGED
        } else {
            null
        }
}
