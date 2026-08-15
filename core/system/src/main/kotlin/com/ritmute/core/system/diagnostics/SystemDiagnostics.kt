package com.ritmute.core.system.diagnostics

import android.app.ActivityManager
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.system.audio.AudioCapabilities
import com.ritmute.core.system.audio.StreamCoupling
import com.ritmute.core.system.dnd.DndController
import com.ritmute.core.system.dnd.DndOverride
import com.ritmute.core.system.scheduler.AlarmScheduler
import com.ritmute.core.system.scheduler.SchedulerHealthStore
import com.ritmute.core.system.scheduler.SchedulerMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App Standby bucket, which decides how many alarms the app is allowed at all.
 *
 * The quotas are the whole reason the app keeps exactly one pending alarm: *active*
 * unlimited, *working set* 10/hour, *frequent* 2/hour, *rare* 1/hour and *restricted*
 * **1 per day**. A user who configures the app once and never opens it again drifts down
 * this list, which is also why an active widget matters — it exempts the app from
 * `RESTRICTED`. See docs/02, decision C5.
 */
enum class StandbyBucket {
    EXEMPTED,
    ACTIVE,
    WORKING_SET,
    FREQUENT,
    RARE,
    RESTRICTED,
    /** Below API 28, or the service was unavailable. */
    UNKNOWN,
}

enum class ForceStopState {
    NOT_DETECTED,
    DETECTED,
    /** Below API 35 there is no way to ask. */
    UNSUPPORTED,
}

/**
 * One honest answer to "will this app actually work on this phone?".
 *
 * This is differentiator D6 in data form. Every field is a fact read from the platform, not
 * an inference, and the diagnostics screen renders them without interpretation.
 */
data class SystemDiagnosticsReport(
    val notificationPolicyAccessGranted: Boolean,
    val notificationsEnabled: Boolean,
    val canScheduleExactAlarms: Boolean,
    val alarmPending: Boolean,
    val schedulerMode: SchedulerMode,
    val nextScheduledAt: Instant?,
    val repairsCount: Int,
    val ignoringBatteryOptimizations: Boolean,
    val standbyBucket: StandbyBucket,
    val volumeFixed: Boolean,
    val ringNotificationCoupling: StreamCoupling,
    val ownedZenRules: Int,
    val dndOverride: DndOverride,
    val forceStop: ForceStopState,
    val lastForceStopDetectedAt: Instant?,
    val vendor: OemVendor,
    val lastError: String?,
) {
    /**
     * The single condition that most needs explaining to the user: the app is in degraded
     * mode, or the platform has quietly taken its alarm away.
     */
    val degraded: Boolean
        get() = !alarmPending ||
            schedulerMode == SchedulerMode.INEXACT ||
            standbyBucket == StandbyBucket.RESTRICTED ||
            forceStop == ForceStopState.DETECTED
}

@Singleton
class SystemDiagnostics @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notificationManager: NotificationManager,
    private val alarmScheduler: AlarmScheduler,
    private val audioCapabilities: AudioCapabilities,
    private val dndController: DndController,
    private val healthStore: SchedulerHealthStore,
) {

    /**
     * @param appliedProfile the profile currently in force, so the DND comparison can say
     *   whether *our* intent is being overridden rather than just what the device is doing.
     */
    suspend fun report(appliedProfile: SoundProfile?): SystemDiagnosticsReport {
        val health = healthStore.current()
        return SystemDiagnosticsReport(
            notificationPolicyAccessGranted = notificationManager.isNotificationPolicyAccessGranted,
            notificationsEnabled = notificationManager.areNotificationsEnabled(),
            canScheduleExactAlarms = alarmScheduler.canScheduleExact(),
            // A missing alarm on a device that should have one is the clearest possible
            // symptom of a force stop or a vendor process killer.
            alarmPending = alarmScheduler.isPending(),
            schedulerMode = health.mode,
            nextScheduledAt = health.nextScheduledAt,
            repairsCount = health.repairsCount,
            ignoringBatteryOptimizations = ignoringBatteryOptimizations(),
            standbyBucket = standbyBucket(),
            volumeFixed = audioCapabilities.isVolumeFixed(),
            ringNotificationCoupling = audioCapabilities.ringNotificationCoupling(),
            ownedZenRules = dndController.ownedRuleCount(),
            dndOverride = dndController.overrideStatus(appliedProfile),
            forceStop = forceStopState(),
            lastForceStopDetectedAt = health.lastForceStopDetectedAt,
            vendor = OemGuidance.detect(),
            lastError = health.lastError,
        )
    }

    private fun ignoringBatteryOptimizations(): Boolean {
        val powerManager = context.getSystemService(PowerManager::class.java) ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun standbyBucket(): StandbyBucket =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bucketFromUsageStats() else StandbyBucket.UNKNOWN

    @RequiresApi(Build.VERSION_CODES.P)
    private fun bucketFromUsageStats(): StandbyBucket {
        val usageStats = context.getSystemService(UsageStatsManager::class.java)
            ?: return StandbyBucket.UNKNOWN

        // Numeric comparisons rather than symbolic ones for the two extremes: EXEMPTED is a
        // hidden constant and RESTRICTED only became public in API 30, so naming either here
        // would be an unresolvable reference or an unguarded API access inside a function
        // that must work from API 28 upwards. The values themselves are frozen platform ABI.
        return when (val bucket = usageStats.appStandbyBucket) {
            in Int.MIN_VALUE..BUCKET_EXEMPTED -> StandbyBucket.EXEMPTED
            UsageStatsManager.STANDBY_BUCKET_ACTIVE -> StandbyBucket.ACTIVE
            UsageStatsManager.STANDBY_BUCKET_WORKING_SET -> StandbyBucket.WORKING_SET
            UsageStatsManager.STANDBY_BUCKET_FREQUENT -> StandbyBucket.FREQUENT
            UsageStatsManager.STANDBY_BUCKET_RARE -> StandbyBucket.RARE
            BUCKET_RESTRICTED -> StandbyBucket.RESTRICTED
            else -> if (bucket > UsageStatsManager.STANDBY_BUCKET_RARE) {
                // Anything worse than RARE that we do not recognise is, by construction of
                // the scale, at least as restricted as RESTRICTED.
                StandbyBucket.RESTRICTED
            } else {
                StandbyBucket.UNKNOWN
            }
        }
    }

    private fun forceStopState(): ForceStopState =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            forceStopFromStartInfo()
        } else {
            ForceStopState.UNSUPPORTED
        }

    /**
     * A force stop — by the user or by a vendor "cleaner" — cancels every pending intent the
     * app owns, disables its widgets, and blocks recovery until the user interacts with the
     * app again. Neither `BOOT_COMPLETED`, nor `WorkManager`, nor the alarm rescues it.
     *
     * There is no technical defence. The only thing the app can do is notice and be honest:
     * "your phone stopped RitMute on the 3rd; between then and now no rule was applied."
     * See docs/02, risk N3.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun forceStopFromStartInfo(): ForceStopState {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: return ForceStopState.UNSUPPORTED
        val history = activityManager.getHistoricalProcessStartReasons(START_INFO_HISTORY)
        return if (history.any { it.wasForceStopped() }) {
            ForceStopState.DETECTED
        } else {
            ForceStopState.NOT_DETECTED
        }
    }

    private companion object {
        /** `UsageStatsManager.STANDBY_BUCKET_EXEMPTED`, hidden from the public SDK. */
        const val BUCKET_EXEMPTED = 5

        /** `UsageStatsManager.STANDBY_BUCKET_RESTRICTED`, public only from API 30. */
        const val BUCKET_RESTRICTED = 45

        /**
         * How many process starts to look back through. One is enough to answer the
         * question for the current start; a handful gives the diagnostics screen a little
         * history without holding a large list in memory.
         */
        const val START_INFO_HISTORY = 5
    }
}
