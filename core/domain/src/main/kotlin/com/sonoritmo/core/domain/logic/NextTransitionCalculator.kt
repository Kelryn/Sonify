package com.sonoritmo.core.domain.logic

import com.sonoritmo.core.domain.model.SchedulingWorld
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Works out when the system next needs to wake up.
 *
 * ## One alarm, not one per window
 *
 * The app keeps **exactly one** pending alarm at any time, for the next moment anything
 * could change. When it fires, the app re-evaluates and schedules the next one.
 *
 * The reason is App Standby Buckets. Alarm quotas are: *active* unlimited, *working set*
 * 10/hour, *frequent* 2/hour, *rare* 1/hour, *restricted* **1 per day**. An app the user
 * configures once and never opens again drifts into *rare* easily. With one alarm per
 * window, a user with six windows burns the hourly quota in one go. With a single alarm,
 * consumption is one wake-up per transition — the theoretical minimum.
 *
 * It also makes the "what is scheduled matches what is configured" invariant checkable in
 * O(1), because there is no public API to enumerate pending alarms.
 */
object NextTransitionCalculator {

    /** How far ahead to look. A week covers any weekly recurrence. */
    const val HORIZON_DAYS = 7L

    /**
     * `setExactAndAllowWhileIdle` cannot fire more than once every ~9 minutes per app in
     * Doze, so two transitions closer than this are merged into one.
     */
    val COALESCE_WINDOW: Duration = Duration.ofMinutes(9)

    /**
     * The next instant at which the desired state could change.
     *
     * Never returns `null`. If there is genuinely nothing to do, it returns a heartbeat at
     * the end of the horizon: an app with no pending alarm stops existing as far as the
     * system is concerned, falls into the *restricted* bucket and never recovers on its own.
     *
     * Sources considered (the first draft of the spec only had the first one, which meant a
     * profile activated "for 2 hours" would never switch itself off unless a window edge
     * happened to fall inside those two hours — the core promise failing in the simplest
     * possible case):
     *
     *  1. Window starts and ends.
     *  2. Expiry of a manual activation.
     *  3. Expiry of a global pause.
     *  4. Time-zone rule transitions (DST), so a clock shift inside a long window is noticed.
     *  5. The horizon heartbeat.
     */
    fun nextTransition(
        world: SchedulingWorld,
        from: Instant,
        horizonDays: Long = HORIZON_DAYS,
    ): Instant {
        val zone = world.zoneId
        val horizonEnd = from.plus(horizonDays, ChronoUnit.DAYS)
        val candidates = sortedSetOf<Instant>()

        fun offer(instant: Instant?) {
            if (instant != null && instant > from && instant <= horizonEnd) candidates.add(instant)
        }

        // 2 + 3 — automation deadlines.
        offer(world.automation.globalPauseUntil)
        offer(world.automation.manualUntil)

        // 1 — window edges.
        val referenceDate = from.atZone(zone).toLocalDate()
        world.activeSchedules.forEach { schedule ->
            val occurrences = ScheduleWindows.occurrencesAround(
                schedule = schedule,
                referenceDate = referenceDate,
                zone = zone,
                backDays = ScheduleWindows.DEFAULT_BACK_DAYS,
                forwardDays = horizonDays,
            )
            occurrences.forEach { occurrence ->
                offer(occurrence.start)
                offer(occurrence.end)
            }
        }

        // 4 — the zone's own rule changes.
        var zoneTransition = zone.rules.nextTransition(from)
        while (zoneTransition != null && zoneTransition.instant <= horizonEnd) {
            offer(zoneTransition.instant)
            zoneTransition = zone.rules.nextTransition(zoneTransition.instant)
        }

        // 5 — heartbeat.
        val first = candidates.firstOrNull() ?: return horizonEnd

        // Merge anything closer than the platform's minimum spacing. The *last* member of
        // the group is chosen: because the desired state is a pure function of the instant,
        // reconciling at that point already produces the correct result for every edge in
        // the group, in one wake-up.
        // Measured from `first`, never from the running choice. Comparing against a
        // sliding value would let a chain of transitions 8 minutes apart drag the window
        // forward indefinitely, so the first several would never be applied on time.
        var chosen = first
        for (candidate in candidates) {
            if (Duration.between(first, candidate) <= COALESCE_WINDOW) {
                chosen = candidate
            } else {
                break
            }
        }

        // Never schedule in the past: that fires immediately and loops.
        return maxOf(chosen, from.plusSeconds(1))
    }
}
