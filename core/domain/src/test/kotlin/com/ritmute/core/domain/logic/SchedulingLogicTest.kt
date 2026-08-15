package com.ritmute.core.domain.logic

import com.google.common.truth.Truth.assertThat
import com.ritmute.core.domain.EPOCH_BASE
import com.ritmute.core.domain.SequentialUuids
import com.ritmute.core.domain.localInstant
import com.ritmute.core.domain.model.ActivationSource
import com.ritmute.core.domain.model.AudioStream
import com.ritmute.core.domain.model.AutomationState
import com.ritmute.core.domain.model.DayMask
import com.ritmute.core.domain.model.DesiredState
import com.ritmute.core.domain.model.LogReason
import com.ritmute.core.domain.model.ProfileTemplate
import com.ritmute.core.domain.model.RingerMode
import com.ritmute.core.domain.model.Schedule
import com.ritmute.core.domain.model.ValidationIssue
import com.ritmute.core.domain.model.VolumeSettings
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.domain.profile
import com.ritmute.core.domain.schedule
import com.ritmute.core.domain.snapshot
import com.ritmute.core.domain.world
import java.time.DayOfWeek
import java.time.Duration
import java.time.temporal.ChronoUnit
import org.junit.Test

/**
 * Conflict resolution, transition calculation, reconciliation planning and volume maths.
 *
 * These mirror the assertions in `tools/selfcheck/DomainSelfCheck.kt`, which exists because
 * this container has no Android toolchain and cannot resolve JUnit. Keeping both means the
 * logic is verifiable locally *and* measured for coverage in CI.
 */
class SchedulingLogicTest {

    private val noon = localInstant("2026-08-17", "12:00") // a Monday

    // ── Conflict resolution ────────────────────────────────────────────────

    @Test
    fun `higher priority wins an overlap`() {
        val w = world(
            profiles = listOf(profile("low", priority = 10), profile("high", priority = 90)),
            schedules = listOf(
                schedule("s-low", "low", "08:00", "20:00"),
                schedule("s-high", "high", "09:00", "18:00"),
            ),
        )
        assertThat(ConflictResolver.resolve(w, noon).activeProfileUuid).isEqualTo("high")
    }

    @Test
    fun `equal priority prefers the shorter window`() {
        val w = world(
            profiles = listOf(profile("a"), profile("b")),
            schedules = listOf(
                schedule("s-a", "a", "08:00", "20:00"),
                schedule("s-b", "b", "11:00", "13:00"),
            ),
        )
        assertThat(ConflictResolver.resolve(w, noon).activeProfileUuid).isEqualTo("b")
    }

    @Test
    fun `identical windows are broken by uuid so two devices agree`() {
        val w = world(
            profiles = listOf(profile("aaa"), profile("bbb")),
            schedules = listOf(
                schedule("s1", "aaa", "08:00", "20:00"),
                schedule("s2", "bbb", "08:00", "20:00"),
            ),
        )
        assertThat(ConflictResolver.resolve(w, noon).activeProfileUuid).isEqualTo("aaa")
    }

    @Test
    fun `a disabled profile never wins`() {
        val w = world(
            profiles = listOf(profile("off", enabled = false)),
            schedules = listOf(schedule("s", "off", "08:00", "20:00")),
        )
        assertThat(ConflictResolver.resolve(w, noon)).isEqualTo(DesiredState.Idle)
    }

    @Test
    fun `manual activation beats the schedule`() {
        val w = world(
            profiles = listOf(profile("scheduled"), profile("manual")),
            schedules = listOf(schedule("s", "scheduled", "08:00", "20:00")),
            automation = AutomationState(
                manualProfileUuid = "manual",
                manualUntil = noon.plus(1, ChronoUnit.HOURS),
            ),
        )
        val resolved = ConflictResolver.resolve(w, noon)
        assertThat(resolved.activeProfileUuid).isEqualTo("manual")
        assertThat((resolved as DesiredState.Active).source).isEqualTo(ActivationSource.MANUAL)
    }

    @Test
    fun `an expired manual activation falls back to the schedule`() {
        val w = world(
            profiles = listOf(profile("scheduled"), profile("manual")),
            schedules = listOf(schedule("s", "scheduled", "08:00", "20:00")),
            automation = AutomationState(
                manualProfileUuid = "manual",
                manualUntil = noon.minusSeconds(1),
            ),
        )
        assertThat(ConflictResolver.resolve(w, noon).activeProfileUuid).isEqualTo("scheduled")
    }

    @Test
    fun `a global pause beats even a manual activation`() {
        val w = world(
            profiles = listOf(profile("manual")),
            automation = AutomationState(
                globalPauseUntil = noon.plus(30, ChronoUnit.MINUTES),
                manualProfileUuid = "manual",
            ),
        )
        assertThat(ConflictResolver.resolve(w, noon)).isInstanceOf(DesiredState.Paused::class.java)
    }

    @Test
    fun `a manual reference to a deleted profile does not crash`() {
        val w = world(
            profiles = listOf(profile("scheduled")),
            schedules = listOf(schedule("s", "scheduled", "08:00", "20:00")),
            automation = AutomationState(manualProfileUuid = "gone"),
        )
        assertThat(ConflictResolver.resolve(w, noon).activeProfileUuid).isEqualTo("scheduled")
    }

    // ── Next transition ────────────────────────────────────────────────────

    @Test
    fun `an empty world still returns a heartbeat`() {
        val next = NextTransitionCalculator.nextTransition(world(), noon)
        assertThat(next).isEqualTo(noon.plus(NextTransitionCalculator.HORIZON_DAYS, ChronoUnit.DAYS))
    }

    /**
     * The regression that mattered most. Without the manual-expiry source, a profile
     * activated "for two hours" would never switch itself off unless a window edge happened
     * to fall inside those two hours.
     */
    @Test
    fun `manual expiry is a transition source of its own`() {
        val expiry = noon.plus(2, ChronoUnit.HOURS)
        val w = world(
            profiles = listOf(profile("p")),
            automation = AutomationState(manualProfileUuid = "p", manualUntil = expiry),
        )
        assertThat(NextTransitionCalculator.nextTransition(w, noon)).isEqualTo(expiry)
    }

    @Test
    fun `pause expiry is a transition source of its own`() {
        val expiry = noon.plus(10, ChronoUnit.MINUTES)
        val w = world(
            profiles = listOf(profile("p")),
            automation = AutomationState(globalPauseUntil = expiry),
        )
        assertThat(NextTransitionCalculator.nextTransition(w, noon)).isEqualTo(expiry)
    }

    @Test
    fun `inside a window the next transition is its end`() {
        val w = world(
            profiles = listOf(profile("p")),
            schedules = listOf(schedule("s", "p", "23:00", "07:00")),
        )
        val midnight = localInstant("2026-08-18", "00:00")
        assertThat(NextTransitionCalculator.nextTransition(w, midnight))
            .isEqualTo(localInstant("2026-08-18", "07:00"))
    }

    /** The platform will not fire twice inside ~9 minutes, so close edges become one. */
    @Test
    fun `edges closer than the coalesce window collapse into the later one`() {
        val w = world(
            profiles = listOf(profile("p"), profile("q")),
            schedules = listOf(
                schedule("s1", "p", "08:00", "09:00"),
                schedule("s2", "q", "09:05", "10:00"),
            ),
        )
        val before = localInstant("2026-08-17", "08:30")
        assertThat(NextTransitionCalculator.nextTransition(w, before))
            .isEqualTo(localInstant("2026-08-17", "09:05"))
    }

    /**
     * Coalescing must be measured from the first candidate. Measuring from a sliding choice
     * would let a chain of 8-minute gaps drag the window forward without limit.
     */
    @Test
    fun `coalescing does not chain indefinitely`() {
        val w = world(
            profiles = listOf(profile("p"), profile("q"), profile("r")),
            schedules = listOf(
                schedule("s1", "p", "08:00", "09:00"),
                schedule("s2", "q", "09:08", "09:30"),
                schedule("s3", "r", "09:16", "09:40"),
            ),
        )
        val before = localInstant("2026-08-17", "08:30")
        val next = NextTransitionCalculator.nextTransition(w, before)
        assertThat(next).isAtMost(localInstant("2026-08-17", "09:09"))
    }

    @Test
    fun `a transition is never scheduled in the past`() {
        val w = world(
            profiles = listOf(profile("p")),
            schedules = listOf(schedule("s", "p", "23:00", "07:00")),
        )
        assertThat(NextTransitionCalculator.nextTransition(w, noon)).isGreaterThan(noon)
    }

    // ── Reconciliation planning ────────────────────────────────────────────

    @Test
    fun `the first application captures a baseline`() {
        val p = profile("p")
        val w = world(profiles = listOf(p), schedules = listOf(schedule("s", "p", "08:00", "20:00")))
        val plan = ReconciliationPlanner.plan(w, DesiredState.Active(p, ActivationSource.SCHEDULE, "s"), noon)
        val action = plan.action as ReconciliationAction.ApplyProfile
        assertThat(action.captureBaseline).isTrue()
    }

    @Test
    fun `a profile to profile switch does not capture a new baseline`() {
        val p = profile("p")
        val other = profile("other")
        val w = world(
            profiles = listOf(p, other),
            automation = AutomationState(appliedProfileUuid = "p"),
            baseline = snapshot(noon.minus(1, ChronoUnit.HOURS)),
        )
        val plan = ReconciliationPlanner.plan(
            w,
            DesiredState.Active(other, ActivationSource.SCHEDULE, "s2"),
            noon,
        )
        assertThat((plan.action as ReconciliationAction.ApplyProfile).captureBaseline).isFalse()
    }

    @Test
    fun `re-applying the same profile is a no-op`() {
        val p = profile("p")
        val w = world(profiles = listOf(p), automation = AutomationState(appliedProfileUuid = "p"))
        val plan = ReconciliationPlanner.plan(w, DesiredState.Active(p, ActivationSource.SCHEDULE, "s"), noon)
        assertThat(plan.isNoOp).isTrue()
    }

    @Test
    fun `a fresh baseline is restored when the window ends`() {
        val w = world(
            profiles = listOf(profile("p")),
            automation = AutomationState(appliedProfileUuid = "p"),
            baseline = snapshot(noon.minus(1, ChronoUnit.HOURS)),
        )
        val plan = ReconciliationPlanner.plan(w, DesiredState.Idle, noon)
        assertThat(plan.action).isInstanceOf(ReconciliationAction.RestoreBaseline::class.java)
    }

    /** Forcing an 11-hour-old state on someone is a bug, not a feature. */
    @Test
    fun `a stale baseline is discarded rather than applied`() {
        val w = world(
            profiles = listOf(profile("p")),
            automation = AutomationState(appliedProfileUuid = "p"),
            baseline = snapshot(noon.minus(Duration.ofDays(3))),
        )
        val plan = ReconciliationPlanner.plan(w, DesiredState.Idle, noon)
        assertThat(plan.action).isNotInstanceOf(ReconciliationAction.RestoreBaseline::class.java)
        assertThat(plan.reason).isEqualTo(LogReason.SKIPPED_STALE_BASELINE)
    }

    @Test
    fun `the default profile is used when the ending profile does not restore`() {
        val w = world(
            profiles = listOf(profile("nr", restoreOnExit = false), profile("fallback")),
            automation = AutomationState(appliedProfileUuid = "nr"),
            defaultProfileUuid = "fallback",
            baseline = snapshot(noon.minusSeconds(60)),
        )
        val plan = ReconciliationPlanner.plan(w, DesiredState.Idle, noon)
        assertThat((plan.action as ReconciliationAction.ApplyProfile).profile.uuid).isEqualTo("fallback")
    }

    @Test
    fun `idle with nothing applied does nothing`() {
        assertThat(ReconciliationPlanner.plan(world(), DesiredState.Idle, noon).isNoOp).isTrue()
    }

    // ── Volume maths ───────────────────────────────────────────────────────

    @Test
    fun `percent maps onto the device step range`() {
        assertThat(VolumeMath.percentToIndex(0, 0, 7)).isEqualTo(0)
        assertThat(VolumeMath.percentToIndex(100, 0, 7)).isEqualTo(7)
        assertThat(VolumeMath.percentToIndex(50, 0, 7)).isEqualTo(4)
    }

    /** VOICE_CALL cannot go to zero, so the minimum has to be respected, not ignored. */
    @Test
    fun `a non-zero stream minimum is respected`() {
        assertThat(VolumeMath.percentToIndex(0, 1, 5)).isEqualTo(1)
        assertThat(VolumeMath.percentToIndex(100, 1, 5)).isEqualTo(5)
    }

    @Test
    fun `a single step device does not divide by zero`() {
        assertThat(VolumeMath.percentToIndex(75, 3, 3)).isEqualTo(3)
        assertThat(VolumeMath.indexToPercent(3, 3, 3)).isEqualTo(0)
    }

    /** Index space is what the watchdog compares; it must round-trip exactly. */
    @Test
    fun `index round trip is stable`() {
        for (steps in 0..7) {
            val percent = VolumeMath.indexToPercent(steps, 0, 7)
            assertThat(VolumeMath.percentToIndex(percent, 0, 7)).isEqualTo(steps)
        }
    }

    @Test
    fun `ramps end exactly on the target`() {
        assertThat(VolumeMath.rampIndices(3, 6)).containsExactly(4, 5, 6).inOrder()
        assertThat(VolumeMath.rampIndices(6, 3)).containsExactly(5, 4, 3).inOrder()
        assertThat(VolumeMath.rampIndices(4, 4)).isEmpty()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `an out of range percent is a programmer error`() {
        VolumeMath.percentToIndex(101, 0, 7)
    }

    // ── Model invariants ───────────────────────────────────────────────────

    @Test
    fun `silent mode drops any ring volume`() {
        val p = profile("s").copy(ringerMode = RingerMode.SILENT, volumes = VolumeSettings(ring = 80))
        assertThat(p.normalized().volumes.ring).isNull()
    }

    @Test
    fun `normal mode raises a zero ring volume to one`() {
        val p = profile("n").copy(ringerMode = RingerMode.NORMAL, volumes = VolumeSettings(ring = 0))
        assertThat(p.normalized().volumes.ring).isEqualTo(1)
    }

    @Test
    fun `a profile that changes nothing is rejected`() {
        val p = profile("e").copy(volumes = VolumeSettings.UNCHANGED, ringerMode = null)
        assertThat(p.validate()).contains(ValidationIssue.PROFILE_CHANGES_NOTHING)
    }

    @Test
    fun `a schedule with no days is invalid`() {
        val s = Schedule(
            uuid = "z",
            profileUuid = "p",
            startMinuteOfDay = 100,
            durationMinutes = 60,
            daysMask = DayMask.NONE,
        )
        assertThat(s.validate()).contains(ValidationIssue.SCHEDULE_NO_DAYS)
    }

    @Test
    fun `a zero length schedule is invalid`() {
        val s = Schedule(
            uuid = "z",
            profileUuid = "p",
            startMinuteOfDay = 100,
            durationMinutes = 0,
            daysMask = DayMask.ALL,
        )
        assertThat(s.validate()).contains(ValidationIssue.SCHEDULE_DURATION_OUT_OF_RANGE)
    }

    @Test
    fun `day masks round trip`() {
        assertThat(DayMask.toSet(DayMask.WEEKDAYS)).hasSize(5)
        assertThat(DayMask.toSet(DayMask.WEEKEND)).hasSize(2)
        assertThat(DayMask.has(DayMask.WEEKDAYS, DayOfWeek.MONDAY)).isTrue()
        assertThat(DayMask.has(DayMask.WEEKDAYS, DayOfWeek.SUNDAY)).isFalse()
        assertThat(DayMask.isValid(DayMask.NONE)).isFalse()
    }

    @Test
    fun `accessibility is not writable and there are six writable streams`() {
        assertThat(AudioStream.ACCESSIBILITY.writable).isFalse()
        assertThat(AudioStream.WRITABLE).hasSize(6)
    }

    // ── Templates ──────────────────────────────────────────────────────────

    @Test
    fun `every template is valid and does something`() {
        val time = TimeSource.fixed(EPOCH_BASE)
        ProfileTemplate.entries.forEach { template ->
            val result = Templates.build(template, template.name, time, SequentialUuids())
            assertThat(result.profile.validate()).isEmpty()
            assertThat(result.profile.changesNothing).isFalse()
            result.schedules.forEach { assertThat(it.validate()).isEmpty() }
        }
    }

    /**
     * The single most important assertion in the file: silencing the alarm along with
     * everything else is the failure users of the competition describe as "the app made me
     * miss work".
     */
    @Test
    fun `the night template never silences the alarm`() {
        val night = Templates.build(
            ProfileTemplate.NIGHT,
            "Noche",
            TimeSource.fixed(EPOCH_BASE),
            SequentialUuids(),
        )
        assertThat(night.profile.volumes.alarm).isEqualTo(100)
        assertThat(night.schedules.single().crossesMidnight).isTrue()
    }

    @Test
    fun `manual-only templates ship without schedules`() {
        val meeting = Templates.build(
            ProfileTemplate.MEETING,
            "Reunión",
            TimeSource.fixed(EPOCH_BASE),
            SequentialUuids(),
        )
        assertThat(meeting.schedules).isEmpty()
    }
}
