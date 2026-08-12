package com.sonoritmo.core.domain.logic

import com.sonoritmo.core.domain.model.ActivationSource
import com.sonoritmo.core.domain.model.AudioSnapshot
import com.sonoritmo.core.domain.model.DesiredState
import com.sonoritmo.core.domain.model.LogReason
import com.sonoritmo.core.domain.model.SchedulingWorld
import com.sonoritmo.core.domain.model.SoundProfile
import java.time.Instant

/** What the system layer should actually do, decided here and executed blindly there. */
sealed interface ReconciliationAction {

    /** Everything already matches. The common case, and it must stay cheap. */
    data object Nothing : ReconciliationAction

    data class ApplyProfile(
        val profile: SoundProfile,
        val source: ActivationSource,
        val scheduleUuid: String?,
        /**
         * True only on the `no automation → some automation` edge. See the single-baseline
         * policy on [AudioSnapshot].
         */
        val captureBaseline: Boolean,
    ) : ReconciliationAction

    data class RestoreBaseline(val snapshot: AudioSnapshot) : ReconciliationAction

    /** Nothing usable to restore; release our DND rule and leave volumes as they are. */
    data object ReleaseOnly : ReconciliationAction
}

data class ReconciliationPlan(
    val action: ReconciliationAction,
    val reason: LogReason,
    val desired: DesiredState,
) {
    val isNoOp: Boolean get() = action is ReconciliationAction.Nothing
}

/**
 * Turns "what should be true" plus "what is true" into "what to do".
 *
 * Kept separate from [ConflictResolver] so that the snapshot lifecycle — the one part of
 * the system that is genuinely stateful — is decided in one small, fully tested place.
 */
object ReconciliationPlanner {

    fun plan(
        world: SchedulingWorld,
        desired: DesiredState,
        now: Instant,
    ): ReconciliationPlan {
        val appliedUuid = world.automation.appliedProfileUuid

        return when (desired) {
            is DesiredState.Active -> planActive(world, desired, appliedUuid)
            is DesiredState.Paused -> planIdle(world, desired, appliedUuid, now, LogReason.GLOBAL_PAUSE_START)
            DesiredState.Idle -> planIdle(world, desired, appliedUuid, now, LogReason.SCHEDULE_END)
        }
    }

    private fun planActive(
        world: SchedulingWorld,
        desired: DesiredState.Active,
        appliedUuid: String?,
    ): ReconciliationPlan {
        if (appliedUuid == desired.profile.uuid) {
            return ReconciliationPlan(
                action = ReconciliationAction.Nothing,
                reason = LogReason.PROFILE_UNCHANGED,
                desired = desired,
            )
        }
        // Baseline is captured only crossing from "nothing active" to "something active",
        // never on profile→profile. A stack of nested snapshots cannot survive the process
        // being killed mid-transition, which is the normal case on aggressive OEMs.
        val captureBaseline = appliedUuid == null && world.baseline == null
        return ReconciliationPlan(
            action = ReconciliationAction.ApplyProfile(
                profile = desired.profile,
                source = desired.source,
                scheduleUuid = desired.scheduleUuid,
                captureBaseline = captureBaseline,
            ),
            reason = when (desired.source) {
                ActivationSource.MANUAL -> LogReason.MANUAL_ACTIVATION
                ActivationSource.SCHEDULE -> LogReason.SCHEDULE_START
            },
            desired = desired,
        )
    }

    private fun planIdle(
        world: SchedulingWorld,
        desired: DesiredState,
        appliedUuid: String?,
        now: Instant,
        endReason: LogReason,
    ): ReconciliationPlan {
        if (appliedUuid == null) {
            return ReconciliationPlan(ReconciliationAction.Nothing, LogReason.PROFILE_UNCHANGED, desired)
        }

        val endingProfile = world.profile(appliedUuid)
        val wantsRestore = endingProfile?.options?.restoreOnExit ?: true
        val baseline = world.baseline

        if (wantsRestore && baseline != null) {
            return if (baseline.isStaleAt(now)) {
                // Nothing coherent left to restore (phone was off for half a day, say).
                // Forcing an 11-hour-old state on the user would be a bug, not a feature.
                ReconciliationPlan(
                    action = fallbackAction(world, desired),
                    reason = LogReason.SKIPPED_STALE_BASELINE,
                    desired = desired,
                )
            } else {
                ReconciliationPlan(
                    action = ReconciliationAction.RestoreBaseline(baseline),
                    reason = endReason,
                    desired = desired,
                )
            }
        }

        return ReconciliationPlan(fallbackAction(world, desired), endReason, desired)
    }

    private fun fallbackAction(
        world: SchedulingWorld,
        desired: DesiredState,
    ): ReconciliationAction {
        val fallback = world.profile(world.defaultProfileUuid)
        return if (fallback != null && fallback.enabled) {
            ReconciliationAction.ApplyProfile(
                profile = fallback,
                source = ActivationSource.SCHEDULE,
                scheduleUuid = null,
                captureBaseline = false,
            )
        } else {
            check(desired !is DesiredState.Active) { "fallback requested for an active state" }
            ReconciliationAction.ReleaseOnly
        }
    }
}
