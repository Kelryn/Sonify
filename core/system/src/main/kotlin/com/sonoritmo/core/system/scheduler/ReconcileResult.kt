package com.sonoritmo.core.system.scheduler

import com.sonoritmo.core.domain.model.ActivityLogEntry
import com.sonoritmo.core.domain.model.AudioSnapshot
import com.sonoritmo.core.domain.model.DesiredState
import com.sonoritmo.core.domain.logic.ReconciliationPlan
import com.sonoritmo.core.domain.model.SchedulingWorld
import com.sonoritmo.core.system.audio.ApplyReport
import com.sonoritmo.core.system.dnd.RuleStatus
import java.time.Instant

/**
 * Everything one reconciliation pass did, and everything the persistence layer has to
 * write down as a consequence.
 *
 * This shape is why `:core:system` can stay independent of `:core:data`. The reconciler
 * receives the world and returns facts; it never opens a database, and it holds no opinion
 * about how any of this is stored. Whoever implements [ReconciliationSink] does the
 * writing, in one transaction, in the module that owns the schema.
 */
data class ReconcileResult(
    val trigger: Trigger,
    val at: Instant,
    /** Zone and offset in force, so the log can reconstruct the local time the user saw. */
    val zoneId: String,
    val utcOffsetSeconds: Int,

    val desired: DesiredState?,
    val plan: ReconciliationPlan?,
    val report: ApplyReport?,
    val dndStatus: RuleStatus?,

    /** When the app will next wake up. Always non-null unless the world could not be read. */
    val nextTransitionAt: Instant?,
    val schedulerMode: SchedulerMode,

    /**
     * True when [appliedProfileUuid] / [appliedScheduleUuid] must be written to
     * `AutomationState`. Distinguishes "leave it alone" from "set it to null".
     */
    val appliedStateChanged: Boolean = false,
    val appliedProfileUuid: String? = null,
    val appliedScheduleUuid: String? = null,

    /** A new baseline to store, captured before the first profile of a run was applied. */
    val capturedBaseline: AudioSnapshot? = null,
    /** The stored baseline was restored (or discarded as stale) and must now be deleted. */
    val baselineConsumed: Boolean = false,

    /** `profileUuid → zenRuleId` assigned by the platform, to persist against the profile. */
    val zenRuleAssignment: Pair<String, String?>? = null,

    /** The watchdog found the device drifted from the active profile and put it back. */
    val repaired: Boolean = false,

    /** Zen rules removed because no profile claims them any more (risk N8). */
    val orphanRulesRemoved: Int = 0,

    val logEntries: List<ActivityLogEntry> = emptyList(),

    /**
     * Class name and message of anything unexpected. The reconciler never throws, so this
     * is the only channel through which a bug becomes visible.
     */
    val error: String? = null,
) {
    val failed: Boolean get() = error != null
}

/**
 * Where the reconciler gets its facts.
 *
 * Implemented in `:app` (or `:core:data`) and injected, so that the coordinator can be
 * driven by a `WorkManager` worker or a receiver with no arguments, while this module
 * keeps knowing nothing about Room. The load happens **inside** the coordinator's mutex,
 * which is what makes "read, decide, apply, write" atomic with respect to other triggers.
 */
fun interface SchedulingWorldSource {
    suspend fun load(): SchedulingWorld
}

/** Where the consequences go. One call, one transaction, at the end of every pass. */
fun interface ReconciliationSink {
    suspend fun persist(result: ReconcileResult)
}
