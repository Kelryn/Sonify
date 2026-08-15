package com.ritmute.core.system.scheduler

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * The safety net: a periodic pass that repairs drift.
 *
 * ## It is a watchdog, not a reconciler
 *
 * The original spec called this "the reconciler" and ran it every 30 minutes. Both were
 * wrong. `WorkManager` cannot promise punctuality — App Standby quotas make anything more
 * frequent than hourly fiction on a phone the user rarely opens, and Android 16 tightens
 * them further — so treating it as the mechanism that delivers transitions on time would be
 * building on sand. The alarm delivers precision; this repairs the consequences of a lost
 * alarm, a killed process or a user who changed a volume by hand.
 *
 * Renaming it mattered: a component named "reconciler" invites someone to relax the alarm
 * strategy because "the reconciler will catch it". It will, up to an hour later.
 *
 * ## Why an hour, a 15-minute flex, and no constraints
 *
 * One hour with a 15-minute flex lets the system batch the work with whatever else it is
 * already doing, which is where the battery budget comes from (RNF-02, and the Play
 * wakelock policy in force since March 2026 — risk N1). No constraints at all: this must run
 * on a phone that is offline, unplugged and idle, because that is exactly the phone whose
 * alarms get killed. `KEEP` so that reopening the app never resets the period, which would
 * otherwise let a user who opens the app frequently postpone the watchdog for ever.
 */
@HiltWorker
class WatchdogWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val coordinator: SchedulerCoordinator,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val trigger = triggerFromInput()
        coordinator.reconcile(trigger)

        // Always success, never retry. `reconcile` does not throw and has already scheduled
        // the next alarm; a retry with exponential backoff would only stack extra wake-ups
        // on top of a device that is already struggling, and the next period is at most an
        // hour away. The failure is recorded in SchedulerHealthStore.lastError instead.
        return Result.success()
    }

    private fun triggerFromInput(): Trigger {
        val name = inputData.getString(KEY_TRIGGER) ?: return Trigger.WATCHDOG
        return Trigger.entries.firstOrNull { it.name == name } ?: Trigger.WATCHDOG
    }

    companion object {
        const val UNIQUE_PERIODIC_NAME = "ritmute-watchdog"
        const val UNIQUE_ONE_SHOT_NAME = "ritmute-reconcile-now"

        private const val KEY_TRIGGER = "trigger"
        private const val REPEAT_INTERVAL_HOURS = 1L
        private const val FLEX_MINUTES = 15L

        /** Idempotent: safe to call from `Application.onCreate` on every cold start. */
        fun enqueuePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<WatchdogWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS,
                FLEX_MINUTES, TimeUnit.MINUTES,
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Runs one pass as soon as the system allows.
         *
         * This is the fallback for the case that cannot be avoided: in degraded (inexact
         * alarm) mode the app has no exemption to start a foreground service from the
         * background, so `ApplyProfileService.start` throws
         * `ForegroundServiceStartNotAllowedException` and the transition has to be delivered
         * by `WorkManager` instead — later, but delivered.
         *
         * `RUN_AS_NON_EXPEDITED_WORK_REQUEST` rather than `DROP`: being late is acceptable,
         * being silently discarded is not.
         */
        fun enqueueOneShot(context: Context, trigger: Trigger) {
            val request = OneTimeWorkRequestBuilder<WatchdogWorker>()
                .setInputData(workDataOf(KEY_TRIGGER to trigger.name))
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONE_SHOT_NAME,
                // A queued pass and a new one are the same request: the desired state is a
                // function of the instant, so the later run subsumes the earlier one.
                ExistingWorkPolicy.REPLACE,
                request,
            )
        }
    }
}
