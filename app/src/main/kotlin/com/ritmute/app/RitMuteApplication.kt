package com.ritmute.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.system.scheduler.AlarmScheduler
import com.ritmute.core.system.scheduler.SchedulerCoordinator
import com.ritmute.core.system.scheduler.SchedulerHealthStore
import com.ritmute.core.system.scheduler.Trigger
import com.ritmute.core.system.scheduler.WatchdogWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class RitMuteApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var coordinator: SchedulerCoordinator

    @Inject lateinit var healthStore: SchedulerHealthStore

    @Inject lateinit var alarmScheduler: AlarmScheduler

    @Inject lateinit var timeSource: TimeSource

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()

        scope.launch {
            detectMissedWork()
            // Every process start is a reconciliation point. This is one of several cheap
            // entry points that replace ACTION_USER_PRESENT, which cannot be declared in a
            // manifest on API 26+ and so would never have been delivered at all.
            coordinator.reconcile(Trigger.USER_INTERACTION)
            WatchdogWorker.enqueuePeriodic(this@RitMuteApplication)
        }
    }

    /**
     * Detects that the app was stopped while a change was due, and records it.
     *
     * A force stop — by the user or by an OEM battery manager — cancels every pending
     * intent, disables widgets and blocks recovery until someone opens the app. There is no
     * technical defence against it. What there is, is the option not to be silent: if the
     * alarm we armed is gone and the moment it was armed for has already passed, something
     * killed us, and the diagnostics screen can say so instead of leaving the user to
     * conclude the app is simply unreliable. See docs/02, risk N3.
     *
     * This is inferred from our own bookkeeping rather than from `ApplicationStartInfo`,
     * which only exists on API 35+; the inference works on every supported version.
     */
    private suspend fun detectMissedWork() {
        val health = healthStore.current()
        val expectedAt = health.nextScheduledAt ?: return
        val overdue = expectedAt < timeSource.now()
        if (overdue && !alarmScheduler.isPending()) {
            healthStore.recordForceStop(timeSource.now())
        }
    }
}
