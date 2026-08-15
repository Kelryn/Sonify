package com.ritmute.core.system.scheduler

import com.ritmute.core.domain.logic.ConflictResolver
import com.ritmute.core.domain.logic.NextTransitionCalculator
import com.ritmute.core.domain.logic.ReconciliationAction
import com.ritmute.core.domain.logic.ReconciliationPlan
import com.ritmute.core.domain.logic.ReconciliationPlanner
import com.ritmute.core.domain.model.ActivityLogEntry
import com.ritmute.core.domain.model.AudioSnapshot
import com.ritmute.core.domain.model.DesiredState
import com.ritmute.core.domain.model.LogReason
import com.ritmute.core.domain.model.LogType
import com.ritmute.core.domain.model.SchedulingWorld
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.domain.port.ZoneProvider
import com.ritmute.core.system.audio.ApplyOutcome
import com.ritmute.core.system.audio.ApplyReport
import com.ritmute.core.system.audio.AudioStateSnapshotter
import com.ritmute.core.system.audio.ProfileAudioApplier
import com.ritmute.core.system.dnd.DndController
import com.ritmute.core.system.dnd.RuleStatus
import com.ritmute.core.system.notification.ProfileChangeNotifier
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single entry point into the automation. Everything — the alarm receiver, the boot
 * receiver, the watchdog, the tile, the widget, the app itself — comes through here.
 *
 * ## Order of operations, and why it is not negotiable
 *
 * ```
 * reconcile(trigger)                       [process-wide Mutex]
 *   1. now = clock.now(); world = worldSource.load()
 *   2. RESCHEDULE FIRST   next = nextTransition(world, now, 7-day horizon)
 *                         alarmScheduler.schedule(next)
 *   3. desired = ConflictResolver.resolve(world, now)
 *   4. plan    = ReconciliationPlanner.plan(desired, applied, snapshot)
 *   5. execute the plan  → audio, DND, activity log
 *   6. finally: RESCHEDULE AGAIN
 * ```
 *
 * Steps 2 and 6 exist because of the single most likely production failure: an exception
 * thrown between "apply" and "reschedule" would break the chain of alarms **for ever** —
 * the app would go quiet, no alarm would be pending, the system would drop it into the
 * `restricted` bucket, and the user's only recovery would be to open the app. Scheduling
 * before doing any work makes that unreachable; scheduling again in a `finally` makes it
 * unreachable twice.
 *
 * ## What this class must never do
 *
 * It takes **no temporal decisions**. Every instant it uses comes from `TimeSource` or from
 * `NextTransitionCalculator`; every choice of profile comes from `ConflictResolver`; every
 * choice of action comes from `ReconciliationPlanner`. This class is glue and error
 * handling, which is precisely why the exit criterion for the phase can be stated as
 * "no function in `:core:system` decides anything about time".
 *
 * And it never throws. A crash here happens inside a broadcast receiver or a worker, where
 * the user sees "RitMute has stopped" for something as mundane as revoked policy access.
 * Failures come back as [ReconcileResult.error]; only coroutine cancellation propagates,
 * as it must.
 */
@Singleton
class SchedulerCoordinator @Inject constructor(
    private val worldSource: SchedulingWorldSource,
    private val sink: ReconciliationSink,
    private val alarmScheduler: AlarmScheduler,
    private val audioApplier: ProfileAudioApplier,
    private val dndController: DndController,
    private val snapshotter: AudioStateSnapshotter,
    private val healthStore: SchedulerHealthStore,
    private val notifier: ProfileChangeNotifier,
    private val timeSource: TimeSource,
    private val zoneProvider: ZoneProvider,
) {

    /**
     * Serialises every pass in the process.
     *
     * Two triggers arriving together is the normal case, not an edge case: the alarm fires
     * at 07:00 while the watchdog runs and the user opens the app. Without this, three
     * passes would interleave their read-modify-write of the applied state and the baseline,
     * and the baseline is the one piece of genuinely destructible user data in the app.
     */
    private val mutex = Mutex()

    suspend fun reconcile(trigger: Trigger): ReconcileResult = mutex.withLock {
        // The zone is read on every pass and never cached: a cached zone survives a
        // TIMEZONE_CHANGED broadcast and then misfires silently for the rest of a trip.
        val zone = zoneProvider.zone()
        val now = timeSource.now()
        val offsetSeconds = zone.rules.getOffset(now).totalSeconds
        val preferAlarmClock = readPreferAlarmClock()

        var scheduledAt: Instant? = null
        var mode = SchedulerMode.INEXACT

        val result: ReconcileResult = try {
            val world = worldSource.load()

            // Step 2 — reschedule before doing anything that could fail.
            val next = NextTransitionCalculator.nextTransition(world, now)
            scheduledAt = next
            mode = alarmScheduler.schedule(next, preferAlarmClock)

            // Steps 3 and 4 — the two pure functions that hold every decision in the app.
            val desired = ConflictResolver.resolve(world, now)
            val plan = ReconciliationPlanner.plan(world, desired, now)

            // Step 5.
            execute(
                trigger = trigger,
                world = world,
                desired = desired,
                plan = plan,
                now = now,
                zone = zone,
                offsetSeconds = offsetSeconds,
                nextTransitionAt = next,
                mode = mode,
            )
        } catch (cancellation: CancellationException) {
            // Structured concurrency: the caller (an ephemeral foreground service that may
            // be killed) has to see its own cancellation. The `finally` below still runs.
            throw cancellation
        } catch (throwable: Throwable) {
            // Even a total failure leaves an alarm pending. An app with no pending alarm
            // stops existing as far as the platform is concerned.
            val fallback = heartbeat(zone, now)
            scheduledAt = fallback
            mode = alarmScheduler.schedule(fallback, preferAlarmClock)
            failure(trigger, now, zone, offsetSeconds, fallback, mode, throwable)
        } finally {
            // Step 6 — reschedule again, whatever happened. Idempotent: the same request
            // code and the same action mean FLAG_UPDATE_CURRENT replaces, never duplicates.
            scheduledAt?.let { alarmScheduler.schedule(it, preferAlarmClock) }
        }

        record(result)
        result
    }

    // ─── Step 5 ──────────────────────────────────────────────────────────────────

    private suspend fun execute(
        trigger: Trigger,
        world: SchedulingWorld,
        desired: DesiredState,
        plan: ReconciliationPlan,
        now: Instant,
        zone: ZoneId,
        offsetSeconds: Int,
        nextTransitionAt: Instant,
        mode: SchedulerMode,
    ): ReconcileResult {
        val logs = mutableListOf<ActivityLogEntry>()
        var capturedBaseline: AudioSnapshot? = null
        var baselineConsumed = false
        var appliedStateChanged = false
        var appliedProfileUuid: String? = null
        var appliedScheduleUuid: String? = null
        var zenRuleAssignment: Pair<String, String?>? = null
        var report: ApplyReport? = null
        var dndStatus: RuleStatus? = null
        var repaired = false

        val previousProfile = world.profile(world.automation.appliedProfileUuid)

        // The planner falls back to "apply the default profile" when a stale baseline cannot
        // be restored. Either way the stale baseline must go, or it will be reconsidered on
        // every single pass from now on.
        if (plan.reason == LogReason.SKIPPED_STALE_BASELINE) baselineConsumed = true

        when (val action = plan.action) {
            is ReconciliationAction.ApplyProfile -> {
                val alreadyApplied = action.profile.uuid == world.automation.appliedProfileUuid
                // Converge, do not repeat. The planner deliberately does not deduplicate the
                // "window ended, fall back to the default profile" case, so without this the
                // app would rewrite the same six volumes and add one identical log line
                // every hour for as long as nothing is scheduled.
                val needsWrite = !alreadyApplied || audioApplier.drift(action.profile).hasDrift
                if (needsWrite) {
                    if (action.captureBaseline) {
                        // Before the first write of a run, and only then: one baseline, never
                        // a stack. A stack cannot survive the process dying mid-transition,
                        // which is the normal case on battery-aggressive OEMs (E-07).
                        capturedBaseline = snapshotter.capture(action.profile.uuid)
                    }

                    // Release the outgoing profile's rule first. Without this, going from
                    // a profile with DND to one without leaves Do Not Disturb switched on
                    // for ever: the incoming profile has nothing to say about DND, so
                    // `apply` returns NotNeeded and never touches the old rule.
                    if (!alreadyApplied) {
                        world.profile(world.automation.appliedProfileUuid)
                            ?.takeIf { it.uuid != action.profile.uuid }
                            ?.let { dndController.release(it) }
                    }

                    dndStatus = dndController.apply(action.profile)
                    zenRuleAssignment = zenAssignmentFor(action.profile, dndStatus)

                    report = audioApplier.apply(action.profile, allowRamp = mode.allowsRamp)

                    if (report.outcome == ApplyOutcome.SKIPPED) {
                        // The profile asked us to stand down (a call is in progress). The
                        // applied state is deliberately *not* advanced, so the next pass —
                        // the watchdog within the hour — tries again.
                        logs += entry(
                            now, zone, offsetSeconds,
                            type = LogType.SKIP,
                            reason = report.skipReason ?: LogReason.SKIPPED_IN_CALL,
                            profile = action.profile,
                            scheduleUuid = action.scheduleUuid,
                            success = true,
                        )
                    } else {
                        appliedStateChanged = true
                        appliedProfileUuid = action.profile.uuid
                        appliedScheduleUuid = action.scheduleUuid
                        repaired = alreadyApplied
                        logs += entry(
                            now, zone, offsetSeconds,
                            type = LogType.APPLY,
                            reason = if (alreadyApplied) LogReason.WATCHDOG_REPAIR else plan.reason,
                            profile = action.profile,
                            scheduleUuid = action.scheduleUuid,
                            success = report.success,
                        )
                        // Not on a watchdog repair: the profile did not change, the device
                        // drifted, and re-announcing something the user already knows is
                        // exactly the noise this app exists to avoid.
                        if (!alreadyApplied && report.success && action.profile.options.notifyOnApply) {
                            notifier.notifyApplied(action.profile.name)
                        }
                    }
                }
            }

            is ReconciliationAction.RestoreBaseline -> {
                report = audioApplier.restore(action.snapshot)
                dndStatus = dndController.release(previousProfile)
                baselineConsumed = true
                appliedStateChanged = true
                logs += entry(
                    now, zone, offsetSeconds,
                    type = LogType.RESTORE,
                    reason = plan.reason,
                    profile = previousProfile,
                    scheduleUuid = world.automation.appliedScheduleUuid,
                    success = report.success,
                )
                // Nothing is active any more, so the note has to go with it. A stale "Night
                // is on" sitting in the shade at noon is worse than never having posted it.
                notifier.clear()
            }

            ReconciliationAction.ReleaseOnly -> {
                dndStatus = dndController.release(previousProfile)
                appliedStateChanged = world.automation.appliedProfileUuid != null
                if (appliedStateChanged) {
                    logs += entry(
                        now, zone, offsetSeconds,
                        type = LogType.RESTORE,
                        reason = plan.reason,
                        profile = previousProfile,
                        scheduleUuid = world.automation.appliedScheduleUuid,
                        success = true,
                    )
                }
                notifier.clear()
            }

            ReconciliationAction.Nothing -> {
                // The bookkeeping agrees with the schedule, but the *device* may not: the
                // user, another app or an OEM layer may have moved a volume. That is the
                // watchdog's actual job — repairing drift, not guaranteeing precision.
                val active = desired as? DesiredState.Active
                if (active != null && trigger.repairsDrift) {
                    val drift = audioApplier.drift(active.profile)
                    if (drift.hasDrift) {
                        report = audioApplier.apply(active.profile, allowRamp = false)
                        dndStatus = dndController.apply(active.profile)
                        zenRuleAssignment = zenAssignmentFor(active.profile, dndStatus)
                        repaired = true
                        logs += entry(
                            now, zone, offsetSeconds,
                            type = LogType.APPLY,
                            reason = LogReason.WATCHDOG_REPAIR,
                            profile = active.profile,
                            scheduleUuid = active.scheduleUuid,
                            success = report.success,
                            detail = drift.streams.joinToString(",") { it.name },
                        )
                    }
                }
            }
        }

        logs += problemEntries(now, zone, offsetSeconds, report, dndStatus, plan)

        var orphansRemoved = 0
        if (trigger.sweepsOrphanRules) {
            val known = world.profilesByUuid.values.mapNotNullTo(HashSet()) { it.zenRuleId }
            orphansRemoved = dndController.sweepOrphanRules(known)
            if (orphansRemoved > 0) {
                logs += entry(
                    now, zone, offsetSeconds,
                    type = LogType.SYSTEM,
                    reason = LogReason.ZEN_RULE_OVERRIDDEN,
                    profile = null,
                    scheduleUuid = null,
                    success = true,
                    detail = "orphanRulesRemoved=$orphansRemoved",
                )
            }
        }

        return ReconcileResult(
            trigger = trigger,
            at = now,
            zoneId = zone.id,
            utcOffsetSeconds = offsetSeconds,
            desired = desired,
            plan = plan,
            report = report,
            dndStatus = dndStatus,
            nextTransitionAt = nextTransitionAt,
            schedulerMode = mode,
            appliedStateChanged = appliedStateChanged,
            appliedProfileUuid = appliedProfileUuid,
            appliedScheduleUuid = appliedScheduleUuid,
            capturedBaseline = capturedBaseline,
            baselineConsumed = baselineConsumed,
            zenRuleAssignment = zenRuleAssignment,
            repaired = repaired,
            orphanRulesRemoved = orphansRemoved,
            logEntries = logs,
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * The zen rule id to persist, or `null` to clear it.
     *
     * Without persisting this, deleting a profile leaves a mode in the system settings that
     * the user can see, cannot explain and cannot remove from our app (E-11, risk N8).
     * Returns `null` — meaning "no write needed" — when the id has not changed.
     */
    private fun zenAssignmentFor(profile: SoundProfile, status: RuleStatus): Pair<String, String?>? {
        if (status is RuleStatus.NotNeeded) return null
        val assigned = status.ruleIdOrNull
        return if (assigned == profile.zenRuleId) null else profile.uuid to assigned
    }

    private fun problemEntries(
        now: Instant,
        zone: ZoneId,
        offsetSeconds: Int,
        report: ApplyReport?,
        dndStatus: RuleStatus?,
        plan: ReconciliationPlan,
    ): List<ActivityLogEntry> {
        val entries = mutableListOf<ActivityLogEntry>()
        val profile = (plan.action as? ReconciliationAction.ApplyProfile)?.profile

        // A write the device accepted and then ignored. This is the differentiator: no other
        // app in this category tells the user that their phone lied to it.
        val ignored = report?.silentlyIgnored.orEmpty()
        if (ignored.isNotEmpty()) {
            entries += entry(
                now, zone, offsetSeconds,
                type = LogType.ERROR,
                reason = LogReason.SILENTLY_IGNORED_BY_SYSTEM,
                profile = profile,
                scheduleUuid = null,
                success = false,
                paramsJson = ignored.joinToString(
                    prefix = "{\"streams\":[\"",
                    separator = "\",\"",
                    postfix = "\"]}",
                ) { it.name },
                // Also in `detail`, which is the field the history screen renders. The JSON
                // above is for machines; without this line the user is told the phone
                // ignored a write and never which one, which is unactionable.
                detail = ignored.joinToString(", ") { it.name },
            )
        } else {
            val worst = report?.worstReason
            if (worst != null && report?.outcome != ApplyOutcome.SKIPPED) {
                entries += entry(
                    now, zone, offsetSeconds,
                    type = if (worst == LogReason.PERMISSION_LOST) LogType.PERMISSION else LogType.ERROR,
                    reason = worst,
                    profile = profile,
                    scheduleUuid = null,
                    success = false,
                )
            }
        }

        val dndReason = dndStatus?.logReason
        if (dndReason != null) {
            entries += entry(
                now, zone, offsetSeconds,
                type = if (dndReason == LogReason.PERMISSION_LOST) LogType.PERMISSION else LogType.ERROR,
                reason = dndReason,
                profile = profile,
                scheduleUuid = null,
                success = false,
            )
        }
        return entries
    }

    private fun entry(
        now: Instant,
        zone: ZoneId,
        offsetSeconds: Int,
        type: LogType,
        reason: LogReason,
        profile: SoundProfile?,
        scheduleUuid: String?,
        success: Boolean,
        detail: String? = null,
        paramsJson: String? = null,
    ): ActivityLogEntry = ActivityLogEntry(
        timestamp = now,
        zoneId = zone.id,
        utcOffsetSeconds = offsetSeconds,
        type = type,
        reason = reason,
        paramsJson = paramsJson,
        profileUuid = profile?.uuid,
        // Denormalised so the audit trail stays readable after the profile is deleted (E-10).
        profileNameSnapshot = profile?.name,
        scheduleUuid = scheduleUuid,
        success = success,
        detail = detail,
    )

    private fun failure(
        trigger: Trigger,
        now: Instant,
        zone: ZoneId,
        offsetSeconds: Int,
        nextTransitionAt: Instant,
        mode: SchedulerMode,
        throwable: Throwable,
    ): ReconcileResult {
        val description = "${throwable.javaClass.simpleName}: ${throwable.message}"

        // Only a SecurityException produces an activity-log line, because it is the only
        // failure the user can act on: the notification policy access or the exact-alarm
        // permission was revoked. `LogReason` is a closed, translatable vocabulary with no
        // "internal error" member, and inventing the nearest-looking one would put a
        // misleading sentence in the one screen that exists to tell the user the truth.
        // Genuine bugs travel in `error`, which the diagnostics screen surfaces verbatim.
        val logs = if (throwable is SecurityException) {
            listOf(
                entry(
                    now, zone, offsetSeconds,
                    type = LogType.PERMISSION,
                    reason = LogReason.SECURITY_EXCEPTION,
                    profile = null,
                    scheduleUuid = null,
                    success = false,
                    detail = description,
                ),
            )
        } else {
            emptyList()
        }

        return ReconcileResult(
            trigger = trigger,
            at = now,
            zoneId = zone.id,
            utcOffsetSeconds = offsetSeconds,
            desired = null,
            plan = null,
            report = null,
            dndStatus = null,
            nextTransitionAt = nextTransitionAt,
            schedulerMode = mode,
            logEntries = logs,
            error = description,
        )
    }

    /**
     * The next wake-up when the world cannot be read at all.
     *
     * Still a domain decision, not one taken here: an empty world has no windows, so
     * `nextTransition` returns its 7-day horizon heartbeat. Combined with the hourly
     * watchdog that is plenty — and it keeps a pending alarm on the books, which is what
     * stops the platform from concluding the app is dormant.
     */
    private fun heartbeat(zone: ZoneId, now: Instant): Instant =
        NextTransitionCalculator.nextTransition(
            world = SchedulingWorld(
                zoneId = zone,
                profilesByUuid = emptyMap(),
                schedules = emptyList(),
            ),
            from = now,
        )

    /**
     * Reading the user's opt-in must never be the reason a transition is missed, so a
     * broken preferences file degrades to "no, do not use setAlarmClock".
     */
    private suspend fun readPreferAlarmClock(): Boolean = try {
        healthStore.current().preferAlarmClock
    } catch (throwable: Throwable) {
        if (throwable is CancellationException) throw throwable
        false
    }

    /**
     * Writes down what happened: telemetry first, then the persistence layer.
     *
     * Both are wrapped, because at this point the audio has already been applied and the
     * alarm is already scheduled. Failing here must not turn a successful transition into a
     * crash inside a broadcast receiver.
     */
    private suspend fun record(result: ReconcileResult) {
        try {
            result.nextTransitionAt?.let { healthStore.recordScheduled(it, result.schedulerMode) }
            healthStore.recordPass(result.at, result.repaired, result.error)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
        }
        try {
            sink.persist(result)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
        }
    }
}
