package com.sonoritmo.app.di

import android.os.Build
import com.sonoritmo.core.data.repository.ActivityLogRepository
import com.sonoritmo.core.data.repository.AutomationRepository
import com.sonoritmo.core.data.repository.ProfileRepository
import com.sonoritmo.core.data.repository.SchedulingWorldRepository
import com.sonoritmo.core.system.scheduler.ReconcileResult
import com.sonoritmo.core.system.scheduler.ReconciliationSink
import com.sonoritmo.core.system.scheduler.SchedulingWorldSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The seam between the scheduler and storage.
 *
 * `:core:system` deliberately does not depend on `:core:data` — the reconciler is handed
 * the world and hands back a result; it never goes looking for a database. That keeps the
 * scheduler testable with a plain fake, and it is `:app`, the only module that knows about
 * everything, that ties the two ends together here.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSchedulingWorldSource(
        repository: SchedulingWorldRepository,
    ): SchedulingWorldSource = SchedulingWorldSource { repository.load() }

    @Provides
    @Singleton
    fun provideReconciliationSink(sink: RoomReconciliationSink): ReconciliationSink = sink
}

/**
 * Writes the consequences of one reconciliation pass.
 *
 * Ordering matters here. The baseline is captured *before* the applied state is recorded,
 * so a process death between the two leaves a baseline that a later pass can still consume,
 * rather than an applied profile whose pre-automation state was never saved. Losing the
 * baseline is the one genuinely destructive failure this app can suffer: it is the user's
 * own settings.
 */
@Singleton
class RoomReconciliationSink @Inject constructor(
    private val automationRepository: AutomationRepository,
    private val activityLogRepository: ActivityLogRepository,
    private val profileRepository: ProfileRepository,
) : ReconciliationSink {

    override suspend fun persist(result: ReconcileResult) {
        result.capturedBaseline?.let { snapshot ->
            automationRepository.captureBaselineIfAbsent(snapshot, deviceFingerprint())
        }

        if (result.baselineConsumed) {
            automationRepository.clearBaseline()
        }

        if (result.appliedStateChanged) {
            automationRepository.recordApplied(
                profileUuid = result.appliedProfileUuid,
                scheduleUuid = result.appliedScheduleUuid,
                at = result.at,
            )
        }

        result.zenRuleAssignment?.let { (profileUuid, ruleId) ->
            profileRepository.setZenRuleId(profileUuid, ruleId)
        }

        automationRepository.recordNextTransition(result.nextTransitionAt)
        automationRepository.recordReconciliation(result.at, result.repaired)

        if (result.logEntries.isNotEmpty()) {
            activityLogRepository.logAll(result.logEntries)
        }
    }

    /**
     * Identifies the hardware a baseline was captured on.
     *
     * Volume step counts differ between devices, so restoring a snapshot taken on another
     * phone would put the wrong absolute levels back. The fingerprint lets the repository
     * discard such a snapshot instead of applying it.
     */
    private fun deviceFingerprint(): String =
        "${Build.MANUFACTURER}/${Build.MODEL}/${Build.VERSION.SDK_INT}"
}
