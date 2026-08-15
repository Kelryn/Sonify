package com.ritmute.core.domain.logic

import com.ritmute.core.domain.model.ActivationSource
import com.ritmute.core.domain.model.DesiredState
import com.ritmute.core.domain.model.SchedulingWorld
import com.ritmute.core.domain.model.SoundProfile
import java.time.Instant

/** A window covering an instant, together with the profile it would activate. */
data class ScheduleCandidate(
    val occurrence: Occurrence,
    val profile: SoundProfile,
)

/**
 * Decides which profile should be in force at a given instant.
 *
 * This is the heart of the system and the piece with the highest test coverage. It is a
 * **total, pure function**: the same world plus the same instant always yields the same
 * answer, on any device, in any order, however many times it is called.
 */
object ConflictResolver {

    /**
     * Ordering used to pick a winner among overlapping windows. First in this order wins.
     *
     * The chain is deliberately built only from **stable** data:
     *
     *  1. `priority` descending — the user's explicit intent.
     *  2. window duration ascending — the more specific window wins.
     *  3. `profile.createdAt` ascending — the older profile wins.
     *  4. `profile.uuid` lexicographically — a total order, identical on every device.
     *
     * The original spec broke ties on the row id, which gives determinism *within* one
     * device but not *between* devices: after an export/import the insertion order would
     * decide who wins an overlap, so two phones holding the same configuration could
     * behave differently. Step 4 removes that. See docs/02, decision D-C6.
     */
    val BY_PRECEDENCE: Comparator<ScheduleCandidate> =
        compareByDescending<ScheduleCandidate> { it.profile.priority }
            .thenBy { it.occurrence.schedule.durationMinutes }
            .thenBy { it.profile.createdAt }
            .thenBy { it.profile.uuid }

    fun resolve(world: SchedulingWorld, now: Instant): DesiredState {
        val automation = world.automation

        // 1. A global pause beats everything, including manual activation.
        val pausedUntil = automation.globalPauseUntil
        if (pausedUntil != null && now < pausedUntil) {
            return DesiredState.Paused(pausedUntil.toEpochMilli())
        }

        // 2. A live manual activation beats anything scheduled.
        if (automation.manualActiveAt(now)) {
            val manual = world.profile(automation.manualProfileUuid)
            if (manual != null && manual.enabled) {
                return DesiredState.Active(manual, ActivationSource.MANUAL, scheduleUuid = null)
            }
            // Dangling reference (profile deleted): fall through to the schedules.
        }

        // 3..5. Whatever is scheduled right now.
        val winner = candidatesAt(world, now).minWithOrNull(BY_PRECEDENCE)
            ?: return DesiredState.Idle

        return DesiredState.Active(
            profile = winner.profile,
            source = ActivationSource.SCHEDULE,
            scheduleUuid = winner.occurrence.schedule.uuid,
        )
    }

    /** Every enabled window covering [now], paired with its profile. */
    fun candidatesAt(world: SchedulingWorld, now: Instant): List<ScheduleCandidate> =
        world.activeSchedules.flatMap { schedule ->
            ScheduleWindows.occurrencesCovering(schedule, now, world.zoneId).mapNotNull { occurrence ->
                world.profile(schedule.profileUuid)?.let { ScheduleCandidate(occurrence, it) }
            }
        }

    /**
     * Ordered explanation of an overlap, best first.
     *
     * Powers the "why does this profile win?" sheet — transparency is a product feature
     * here, not a debug affordance.
     */
    fun explain(world: SchedulingWorld, now: Instant): List<ScheduleCandidate> =
        candidatesAt(world, now).sortedWith(BY_PRECEDENCE)
}
