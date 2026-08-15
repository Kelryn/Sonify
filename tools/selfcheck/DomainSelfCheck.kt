@file:Suppress("TooManyFunctions")

package com.ritmute.tools.selfcheck

import com.ritmute.core.domain.logic.ConflictResolver
import com.ritmute.core.domain.logic.EdgeKind
import com.ritmute.core.domain.logic.NextTransitionCalculator
import com.ritmute.core.domain.logic.ReconciliationAction
import com.ritmute.core.domain.logic.ReconciliationPlanner
import com.ritmute.core.domain.logic.ScheduleWindows
import com.ritmute.core.domain.logic.Templates
import com.ritmute.core.domain.logic.VolumeMath
import com.ritmute.core.domain.model.ActivationSource
import com.ritmute.core.domain.model.AudioSnapshot
import com.ritmute.core.domain.model.AudioStream
import com.ritmute.core.domain.model.AutomationState
import com.ritmute.core.domain.model.DayMask
import com.ritmute.core.domain.model.DesiredState
import com.ritmute.core.domain.model.ProfileOptions
import com.ritmute.core.domain.model.ProfileTemplate
import com.ritmute.core.domain.model.RingerMode
import com.ritmute.core.domain.model.Schedule
import com.ritmute.core.domain.model.SchedulingWorld
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.domain.model.StreamLevel
import com.ritmute.core.domain.model.VolumeSettings
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.domain.port.UuidGenerator
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

// ─────────────────────────────────────────────────────────────────────────────
//  Local self-check for :core:domain.
//
//  The development container has no Android SDK and no access to maven.google.com,
//  so the JUnit + Truth suite can only run in CI. This file is the local safety net:
//  it exercises exactly the same pure logic with zero third-party dependencies, so it
//  can be compiled and RUN with nothing but kotlinc.
//
//  Usage:
//    kotlinc core/domain/src/main/**/*.kt tools/selfcheck/DomainSelfCheck.kt \
//      -include-runtime -d /tmp/selfcheck.jar
//    java -jar /tmp/selfcheck.jar
//
//  It is not a replacement for the CI suite. It is what makes it possible to catch a
//  broken DST calculation without waiting for a runner.
// ─────────────────────────────────────────────────────────────────────────────

private val MADRID: ZoneId = ZoneId.of("Europe/Madrid")
private val BASE: Instant = Instant.parse("2026-01-01T00:00:00Z")

private var checks = 0
private val failures = mutableListOf<String>()

private fun check(label: String, condition: Boolean, detail: String = "") {
    checks++
    if (!condition) failures += "$label${if (detail.isEmpty()) "" else " — $detail"}"
}

private fun <T> checkEquals(label: String, expected: T, actual: T) {
    check(label, expected == actual, "expected <$expected> but was <$actual>")
}

private fun at(date: String, time: String, zone: ZoneId = MADRID): Instant =
    LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

private class Uuids(private val prefix: String = "u") : UuidGenerator {
    private var n = 0
    override fun newUuid(): String = "$prefix${++n}"
}

private fun profile(
    uuid: String,
    priority: Int = 50,
    createdAt: Instant = BASE,
    restoreOnExit: Boolean = true,
    enabled: Boolean = true,
) = SoundProfile(
    uuid = uuid,
    name = uuid,
    priority = priority,
    enabled = enabled,
    volumes = VolumeSettings(music = 30),
    options = ProfileOptions(restoreOnExit = restoreOnExit),
    createdAt = createdAt,
    updatedAt = createdAt,
)

private fun sched(
    uuid: String,
    profileUuid: String,
    start: String,
    end: String,
    daysMask: Int = DayMask.ALL,
): Schedule {
    val s = LocalTime.parse(start)
    val e = LocalTime.parse(end)
    return Schedule.fromWallClock(
        uuid = uuid,
        profileUuid = profileUuid,
        startHour = s.hour,
        startMinute = s.minute,
        endHour = e.hour,
        endMinute = e.minute,
        daysMask = daysMask,
    )
}

private fun world(
    profiles: List<SoundProfile> = emptyList(),
    schedules: List<Schedule> = emptyList(),
    automation: AutomationState = AutomationState.EMPTY,
    defaultProfileUuid: String? = null,
    baseline: AudioSnapshot? = null,
    zone: ZoneId = MADRID,
) = SchedulingWorld.of(zone, profiles, schedules, automation, defaultProfileUuid, baseline)

private fun snapshot(capturedAt: Instant) = AudioSnapshot(
    capturedAt = capturedAt,
    levels = mapOf(AudioStream.MUSIC to StreamLevel(10, 0, 15)),
    ringerMode = RingerMode.NORMAL,
    interruptionFilter = 1,
)

// ── 1. Midnight crossing and weekday semantics ───────────────────────────────

private fun midnightCrossing() {
    val saturdayNight = sched("s1", "p1", "23:00", "07:00", DayMask.of(DayOfWeek.SATURDAY))

    // 2026-08-15 is a Saturday.
    check(
        "sat window still active sunday 01:00",
        ScheduleWindows.occurrencesCovering(saturdayNight, at("2026-08-16", "01:00"), MADRID).size == 1,
    )
    check(
        "sat window does not restart sunday 23:30",
        ScheduleWindows.occurrencesCovering(saturdayNight, at("2026-08-16", "23:30"), MADRID).isEmpty(),
    )

    val nightly = sched("s2", "p1", "23:00", "07:00")
    check(
        "half open: 06:59 inside",
        ScheduleWindows.occurrencesCovering(nightly, at("2026-08-16", "06:59"), MADRID).size == 1,
    )
    check(
        "half open: 07:00 outside",
        ScheduleWindows.occurrencesCovering(nightly, at("2026-08-16", "07:00"), MADRID).isEmpty(),
    )
    check(
        "half open: 23:00 inside",
        ScheduleWindows.occurrencesCovering(nightly, at("2026-08-15", "23:00"), MADRID).size == 1,
    )

    val allDay = sched("s3", "p1", "09:00", "09:00")
    checkEquals("start == end is 24h", 1440, allDay.durationMinutes)
    check("start == end crosses midnight", allDay.crossesMidnight)

    val monday = sched("s4", "p1", "00:00", "00:00", DayMask.of(DayOfWeek.MONDAY))
    val occurrence = ScheduleWindows.occurrenceStartingOn(monday, LocalDate.parse("2026-08-17"), MADRID)
    check("monday 00:00-00:00 exists", occurrence != null)
    if (occurrence != null) {
        checkEquals("monday window start", at("2026-08-17", "00:00"), occurrence.start)
        checkEquals("monday window end", at("2026-08-18", "00:00"), occurrence.end)
    }

    val weekdays = sched("s5", "p1", "09:00", "18:00", DayMask.WEEKDAYS)
    check(
        "weekday schedule skips sunday",
        ScheduleWindows.occurrenceStartingOn(weekdays, LocalDate.parse("2026-08-16"), MADRID) == null,
    )
}

// ── 2. Daylight saving time ──────────────────────────────────────────────────

private fun daylightSaving() {
    // Madrid springs forward on 2026-03-29 at 01:00 UTC (02:00 → 03:00 local).
    checkEquals(
        "gap START collapses to the jump",
        Instant.parse("2026-03-29T01:00:00Z"),
        ScheduleWindows.resolveLocal(LocalDate.parse("2026-03-29"), 150, MADRID, EdgeKind.START),
    )
    checkEquals(
        "gap END collapses to the jump",
        Instant.parse("2026-03-29T01:00:00Z"),
        ScheduleWindows.resolveLocal(LocalDate.parse("2026-03-29"), 150, MADRID, EdgeKind.END),
    )

    // Madrid falls back on 2026-10-25 at 01:00 UTC (03:00 → 02:00 local): 02:30 twice.
    checkEquals(
        "overlap START prefers the first pass",
        Instant.parse("2026-10-25T00:30:00Z"),
        ScheduleWindows.resolveLocal(LocalDate.parse("2026-10-25"), 150, MADRID, EdgeKind.START),
    )
    checkEquals(
        "overlap END prefers the second pass",
        Instant.parse("2026-10-25T01:30:00Z"),
        ScheduleWindows.resolveLocal(LocalDate.parse("2026-10-25"), 150, MADRID, EdgeKind.END),
    )

    val nightly = sched("s1", "p1", "23:00", "07:00")

    val spring = ScheduleWindows.occurrenceStartingOn(nightly, LocalDate.parse("2026-03-28"), MADRID)
    check("spring night window exists", spring != null)
    if (spring != null) {
        checkEquals(
            "night across spring forward is 7h",
            Duration.ofHours(7),
            Duration.between(spring.start, spring.end),
        )
    }

    val autumn = ScheduleWindows.occurrenceStartingOn(nightly, LocalDate.parse("2026-10-24"), MADRID)
    check("autumn night window exists", autumn != null)
    if (autumn != null) {
        checkEquals(
            "night across fall back is 9h",
            Duration.ofHours(9),
            Duration.between(autumn.start, autumn.end),
        )
    }
}

// ── 3. Conflict resolution ───────────────────────────────────────────────────

private fun conflictResolution() {
    val low = profile("low", priority = 10)
    val high = profile("high", priority = 90)
    val schedules = listOf(
        sched("s-low", "low", "08:00", "20:00"),
        sched("s-high", "high", "09:00", "18:00"),
    )
    val w = world(listOf(low, high), schedules)

    val noon = at("2026-08-17", "12:00")
    val resolved = ConflictResolver.resolve(w, noon)
    check("higher priority wins an overlap", resolved is DesiredState.Active)
    checkEquals("winner is the high priority profile", "high", resolved.activeProfileUuid)

    // Equal priority → shorter window wins.
    val a = profile("a", priority = 50, createdAt = BASE)
    val b = profile("b", priority = 50, createdAt = BASE.plusSeconds(10))
    val tie = world(
        listOf(a, b),
        listOf(sched("s-a", "a", "08:00", "20:00"), sched("s-b", "b", "11:00", "13:00")),
    )
    checkEquals(
        "equal priority: shorter window wins",
        "b",
        ConflictResolver.resolve(tie, noon).activeProfileUuid,
    )

    // Same priority and same duration → older profile wins.
    val older = profile("zzz-older", priority = 50, createdAt = BASE)
    val newer = profile("aaa-newer", priority = 50, createdAt = BASE.plusSeconds(60))
    val sameShape = world(
        listOf(older, newer),
        listOf(sched("s-o", "zzz-older", "08:00", "20:00"), sched("s-n", "aaa-newer", "08:00", "20:00")),
    )
    checkEquals(
        "equal priority and duration: older profile wins",
        "zzz-older",
        ConflictResolver.resolve(sameShape, noon).activeProfileUuid,
    )

    // Fully identical → uuid decides, so the answer is stable across devices.
    val u1 = profile("aaa", priority = 50, createdAt = BASE)
    val u2 = profile("bbb", priority = 50, createdAt = BASE)
    val identical = world(
        listOf(u1, u2),
        listOf(sched("s1", "aaa", "08:00", "20:00"), sched("s2", "bbb", "08:00", "20:00")),
    )
    checkEquals(
        "final tie break is the uuid",
        "aaa",
        ConflictResolver.resolve(identical, noon).activeProfileUuid,
    )

    // No window → Idle.
    checkEquals(
        "no window means Idle",
        DesiredState.Idle,
        ConflictResolver.resolve(w, at("2026-08-17", "03:00")),
    )

    // Disabled profile is ignored.
    val disabled = world(listOf(profile("off", enabled = false)), listOf(sched("s", "off", "08:00", "20:00")))
    checkEquals("disabled profile is ignored", DesiredState.Idle, ConflictResolver.resolve(disabled, noon))
}

private fun manualAndPause() {
    val scheduled = profile("scheduled")
    val manual = profile("manual")
    val noon = at("2026-08-17", "12:00")

    // Manual activation beats the schedule.
    val manualWorld = world(
        listOf(scheduled, manual),
        listOf(sched("s", "scheduled", "08:00", "20:00")),
        AutomationState(manualProfileUuid = "manual", manualUntil = noon.plusSeconds(3600)),
    )
    checkEquals(
        "manual activation beats the schedule",
        "manual",
        ConflictResolver.resolve(manualWorld, noon).activeProfileUuid,
    )

    // Expired manual activation falls back to the schedule.
    val expired = world(
        listOf(scheduled, manual),
        listOf(sched("s", "scheduled", "08:00", "20:00")),
        AutomationState(manualProfileUuid = "manual", manualUntil = noon.minusSeconds(1)),
    )
    checkEquals(
        "expired manual activation falls back",
        "scheduled",
        ConflictResolver.resolve(expired, noon).activeProfileUuid,
    )

    // Indefinite manual activation never expires.
    val indefinite = world(
        listOf(manual),
        emptyList(),
        AutomationState(manualProfileUuid = "manual", manualUntil = null),
    )
    checkEquals(
        "indefinite manual activation stays",
        "manual",
        ConflictResolver.resolve(indefinite, noon.plusSeconds(864_000)).activeProfileUuid,
    )

    // A global pause beats everything, manual included.
    val paused = world(
        listOf(scheduled, manual),
        listOf(sched("s", "scheduled", "08:00", "20:00")),
        AutomationState(
            globalPauseUntil = noon.plusSeconds(1800),
            manualProfileUuid = "manual",
            manualUntil = null,
        ),
    )
    check("global pause beats everything", ConflictResolver.resolve(paused, noon) is DesiredState.Paused)

    // Dangling manual reference (profile deleted) falls through instead of crashing.
    val dangling = world(
        listOf(scheduled),
        listOf(sched("s", "scheduled", "08:00", "20:00")),
        AutomationState(manualProfileUuid = "deleted", manualUntil = null),
    )
    checkEquals(
        "dangling manual reference falls through",
        "scheduled",
        ConflictResolver.resolve(dangling, noon).activeProfileUuid,
    )
}

// ── 4. Next transition ───────────────────────────────────────────────────────

private fun nextTransition() {
    val p = profile("p")
    val night = sched("s", "p", "23:00", "07:00")
    val w = world(listOf(p), listOf(night))

    val eightPm = at("2026-08-17", "20:00")
    checkEquals(
        "next transition is the upcoming window start",
        at("2026-08-17", "23:00"),
        NextTransitionCalculator.nextTransition(w, eightPm),
    )

    val midnight = at("2026-08-18", "00:00")
    checkEquals(
        "inside a window, next transition is its end",
        at("2026-08-18", "07:00"),
        NextTransitionCalculator.nextTransition(w, midnight),
    )

    // Heartbeat: no schedules at all still yields an alarm, or the app disappears
    // from the system's point of view and never recovers.
    val empty = world()
    checkEquals(
        "empty world returns the horizon heartbeat",
        eightPm.plus(NextTransitionCalculator.HORIZON_DAYS, java.time.temporal.ChronoUnit.DAYS),
        NextTransitionCalculator.nextTransition(empty, eightPm),
    )

    // The regression that mattered most: a manual activation with a duration must
    // generate its own transition, or a profile activated "for 2 hours" never ends.
    val manualOnly = world(
        listOf(p),
        emptyList(),
        AutomationState(manualProfileUuid = "p", manualUntil = eightPm.plusSeconds(7200)),
    )
    checkEquals(
        "manual expiry is a transition source",
        eightPm.plusSeconds(7200),
        NextTransitionCalculator.nextTransition(manualOnly, eightPm),
    )

    // Global pause expiry, likewise.
    val pausedOnly = world(
        listOf(p),
        emptyList(),
        AutomationState(globalPauseUntil = eightPm.plusSeconds(600)),
    )
    checkEquals(
        "pause expiry is a transition source",
        eightPm.plusSeconds(600),
        NextTransitionCalculator.nextTransition(pausedOnly, eightPm),
    )

    // Never in the past.
    check(
        "transition is always in the future",
        NextTransitionCalculator.nextTransition(w, eightPm) > eightPm,
    )

    // Coalescing: two edges 5 minutes apart collapse into the later one, because the
    // platform will not fire twice inside ~9 minutes.
    val q = profile("q")
    val chained = world(
        listOf(p, q),
        listOf(sched("s1", "p", "08:00", "09:00"), sched("s2", "q", "09:05", "10:00")),
    )
    val before = at("2026-08-17", "08:30")
    checkEquals(
        "close transitions coalesce to the later edge",
        at("2026-08-17", "09:05"),
        NextTransitionCalculator.nextTransition(chained, before),
    )

    // A DST rule change inside a long window is itself a transition.
    val allDay = sched("s-all", "p", "00:00", "00:00")
    val dstWorld = world(listOf(p), listOf(allDay))
    val beforeJump = Instant.parse("2026-03-29T00:00:00Z")
    val next = NextTransitionCalculator.nextTransition(dstWorld, beforeJump)
    check("dst transition is considered", next <= Instant.parse("2026-03-29T01:00:00Z"))
}

// ── 5. Reconciliation planning ───────────────────────────────────────────────

private fun reconciliation() {
    val p = profile("p")
    val noon = at("2026-08-17", "12:00")
    val active = DesiredState.Active(p, ActivationSource.SCHEDULE, "s")

    // Nothing applied yet → apply and capture the baseline.
    val fresh = world(listOf(p), listOf(sched("s", "p", "08:00", "20:00")))
    val firstPlan = ReconciliationPlanner.plan(fresh, active, noon)
    val firstAction = firstPlan.action
    check("first apply captures the baseline", firstAction is ReconciliationAction.ApplyProfile)
    if (firstAction is ReconciliationAction.ApplyProfile) {
        check("captureBaseline is true on none -> some", firstAction.captureBaseline)
    }

    // Already applied → no-op. Idempotence.
    val applied = world(
        listOf(p),
        listOf(sched("s", "p", "08:00", "20:00")),
        AutomationState(appliedProfileUuid = "p"),
        baseline = snapshot(noon.minusSeconds(3600)),
    )
    check("re-applying the same profile is a no-op", ReconciliationPlanner.plan(applied, active, noon).isNoOp)

    // Profile → profile must NOT capture a new baseline.
    val other = profile("other")
    val switching = world(
        listOf(p, other),
        emptyList(),
        AutomationState(appliedProfileUuid = "p"),
        baseline = snapshot(noon.minusSeconds(3600)),
    )
    val switchAction = ReconciliationPlanner.plan(
        switching,
        DesiredState.Active(other, ActivationSource.SCHEDULE, "s2"),
        noon,
    ).action
    check("profile -> profile applies", switchAction is ReconciliationAction.ApplyProfile)
    if (switchAction is ReconciliationAction.ApplyProfile) {
        check("profile -> profile does not re-capture", !switchAction.captureBaseline)
    }

    // Window ends with restoreOnExit and a fresh baseline → restore it.
    val ending = world(
        listOf(p),
        emptyList(),
        AutomationState(appliedProfileUuid = "p"),
        baseline = snapshot(noon.minusSeconds(3600)),
    )
    check(
        "ending restores a fresh baseline",
        ReconciliationPlanner.plan(ending, DesiredState.Idle, noon).action
            is ReconciliationAction.RestoreBaseline,
    )

    // A stale baseline is discarded rather than forced on the user.
    val stale = world(
        listOf(p),
        emptyList(),
        AutomationState(appliedProfileUuid = "p"),
        baseline = snapshot(noon.minus(Duration.ofDays(3))),
    )
    val stalePlan = ReconciliationPlanner.plan(stale, DesiredState.Idle, noon)
    check("stale baseline is skipped", stalePlan.action !is ReconciliationAction.RestoreBaseline)
    checkEquals(
        "stale baseline is logged as such",
        com.ritmute.core.domain.model.LogReason.SKIPPED_STALE_BASELINE,
        stalePlan.reason,
    )

    // restoreOnExit = false with a default profile → apply the default.
    val noRestore = profile("nr", restoreOnExit = false)
    val fallback = profile("fallback")
    val withDefault = world(
        listOf(noRestore, fallback),
        emptyList(),
        AutomationState(appliedProfileUuid = "nr"),
        defaultProfileUuid = "fallback",
        baseline = snapshot(noon.minusSeconds(60)),
    )
    val fallbackAction = ReconciliationPlanner.plan(withDefault, DesiredState.Idle, noon).action
    check("falls back to the default profile", fallbackAction is ReconciliationAction.ApplyProfile)
    if (fallbackAction is ReconciliationAction.ApplyProfile) {
        checkEquals("default profile is used", "fallback", fallbackAction.profile.uuid)
    }

    // Nothing applied and nothing desired → nothing to do.
    check(
        "idle with nothing applied is a no-op",
        ReconciliationPlanner.plan(world(), DesiredState.Idle, noon).isNoOp,
    )

    // A global pause behaves like Idle for the purposes of applying.
    val pausedPlan = ReconciliationPlanner.plan(
        ending,
        DesiredState.Paused(noon.plusSeconds(600).toEpochMilli()),
        noon,
    )
    check("pause releases the applied profile", !pausedPlan.isNoOp)
}

// ── 6. Volume mathematics ────────────────────────────────────────────────────

private fun volumeMath() {
    checkEquals("0% on a 0..7 stream", 0, VolumeMath.percentToIndex(0, 0, 7))
    checkEquals("100% on a 0..7 stream", 7, VolumeMath.percentToIndex(100, 0, 7))
    checkEquals("50% on a 0..7 stream", 4, VolumeMath.percentToIndex(50, 0, 7))

    // The minimum is respected, not ignored: VOICE_CALL usually cannot go to 0.
    checkEquals("0% respects a minimum of 1", 1, VolumeMath.percentToIndex(0, 1, 5))
    checkEquals("100% with a minimum of 1", 5, VolumeMath.percentToIndex(100, 1, 5))

    // Degenerate device (single step) must not divide by zero.
    checkEquals("single step device", 3, VolumeMath.percentToIndex(75, 3, 3))
    checkEquals("single step device inverse", 0, VolumeMath.indexToPercent(3, 3, 3))

    // Round trip is stable in index space, which is what the watchdog compares.
    for (steps in 0..7) {
        val percent = VolumeMath.indexToPercent(steps, 0, 7)
        checkEquals("index round trip at $steps", steps, VolumeMath.percentToIndex(percent, 0, 7))
    }

    checkEquals("ramp up", listOf(4, 5, 6), VolumeMath.rampIndices(3, 6))
    checkEquals("ramp down", listOf(5, 4, 3), VolumeMath.rampIndices(6, 3))
    checkEquals("no ramp needed", emptyList(), VolumeMath.rampIndices(4, 4))

    check(
        "out of range percent is a programmer error",
        runCatching { VolumeMath.percentToIndex(101, 0, 7) }.isFailure,
    )
}

// ── 7. Model invariants ──────────────────────────────────────────────────────

private fun modelInvariants() {
    // SILENT drops any ring volume: they are contradictory orders whose outcome would
    // otherwise depend on apply order.
    val silent = profile("s").copy(ringerMode = RingerMode.SILENT, volumes = VolumeSettings(ring = 80))
    checkEquals("SILENT normalises ring away", null, silent.normalized().volumes.ring)

    // NORMAL with ring = 0 is raised to 1.
    val normal = profile("n").copy(ringerMode = RingerMode.NORMAL, volumes = VolumeSettings(ring = 0))
    checkEquals("NORMAL raises ring 0 to 1", 1, normal.normalized().volumes.ring)

    val empty = profile("e").copy(volumes = VolumeSettings.UNCHANGED, ringerMode = null)
    check("a profile that changes nothing is detected", empty.changesNothing)
    check(
        "validation reports the empty profile",
        empty.validate().contains(com.ritmute.core.domain.model.ValidationIssue.PROFILE_CHANGES_NOTHING),
    )

    check("day mask rejects zero", !DayMask.isValid(DayMask.NONE))
    check("day mask accepts all days", DayMask.isValid(DayMask.ALL))
    checkEquals("weekday mask has five days", 5, DayMask.toSet(DayMask.WEEKDAYS).size)
    checkEquals("weekend mask has two days", 2, DayMask.toSet(DayMask.WEEKEND).size)
    check("monday is a weekday", DayMask.has(DayMask.WEEKDAYS, DayOfWeek.MONDAY))
    check("sunday is not a weekday", !DayMask.has(DayMask.WEEKDAYS, DayOfWeek.SUNDAY))

    checkEquals("accessibility is not writable", false, AudioStream.ACCESSIBILITY.writable)
    checkEquals("six writable streams", 6, AudioStream.WRITABLE.size)

    val zeroDuration = Schedule(
        uuid = "z",
        profileUuid = "p",
        startMinuteOfDay = 100,
        durationMinutes = 0,
        daysMask = DayMask.ALL,
    )
    check("zero duration is invalid", zeroDuration.validate().isNotEmpty())
}

// ── 8. Templates ─────────────────────────────────────────────────────────────

private fun templates() {
    val time = TimeSource.fixed(BASE)
    ProfileTemplate.entries.forEach { template ->
        val result = Templates.build(template, template.name, time, Uuids())
        check("${template.name} template validates", result.profile.validate().isEmpty())
        check("${template.name} template does something", !result.profile.changesNothing)
        result.schedules.forEach { schedule ->
            check("${template.name} schedule validates", schedule.validate().isEmpty())
            checkEquals(
                "${template.name} schedule points at its profile",
                result.profile.uuid,
                schedule.profileUuid,
            )
        }
    }

    // The one that matters most: night must never silence the alarm.
    val night = Templates.build(ProfileTemplate.NIGHT, "Noche", time, Uuids())
    checkEquals("night keeps the alarm at 100", 100, night.profile.volumes.alarm)
    checkEquals("night has one window", 1, night.schedules.size)
    check("night window crosses midnight", night.schedules.single().crossesMidnight)

    val work = Templates.build(ProfileTemplate.WORK, "Trabajo", time, Uuids())
    checkEquals("work runs on weekdays", DayMask.WEEKDAYS, work.schedules.single().daysMask)
    checkEquals("work keeps the alarm at 100", 100, work.profile.volumes.alarm)

    val meeting = Templates.build(ProfileTemplate.MEETING, "Reunión", time, Uuids())
    check("meeting is manual only", meeting.schedules.isEmpty())
}

// ── Entry point ──────────────────────────────────────────────────────────────

fun main() {
    midnightCrossing()
    daylightSaving()
    conflictResolution()
    manualAndPause()
    nextTransition()
    reconciliation()
    volumeMath()
    modelInvariants()
    templates()

    println("RitMute :core:domain self-check")
    println("  checks run : $checks")
    println("  failures   : ${failures.size}")
    if (failures.isNotEmpty()) {
        println()
        failures.forEach { println("  FAIL  $it") }
        kotlin.system.exitProcess(1)
    }
    println("  OK")
}
