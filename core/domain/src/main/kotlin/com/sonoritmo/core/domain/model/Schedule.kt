package com.sonoritmo.core.domain.model

import java.time.DayOfWeek

/**
 * Bitmask helpers for the days a schedule runs on.
 *
 * Persisted as an `INTEGER` rather than `"MON,TUE"` text: it is filterable in SQL, the
 * "at least one day" invariant becomes a one-line `CHECK`, and it is immune to enum
 * renames and locale. See docs/02, amendment E-02.
 *
 * Bit `n` corresponds to ISO-8601 day `n + 1` (Monday = 1).
 */
object DayMask {
    const val NONE = 0
    const val ALL = 0b1111111
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000

    fun of(vararg days: DayOfWeek): Int = days.fold(NONE) { acc, d -> acc or bit(d) }

    fun of(days: Iterable<DayOfWeek>): Int = days.fold(NONE) { acc, d -> acc or bit(d) }

    fun bit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    operator fun contains(pair: Pair<Int, DayOfWeek>): Boolean = pair.first and bit(pair.second) != 0

    fun has(mask: Int, day: DayOfWeek): Boolean = mask and bit(day) != 0

    fun toSet(mask: Int): Set<DayOfWeek> =
        DayOfWeek.entries.filterTo(LinkedHashSet()) { has(mask, it) }

    fun isValid(mask: Int): Boolean = mask in 1..ALL
}

/**
 * A recurring time window during which a profile should be active.
 *
 * ## Why start + duration instead of start + end
 *
 * The original spec used `startTime` / `endTime` with the rule "if `end <= start` it
 * crosses midnight". That collapses two different things into `start == end` (an empty
 * window or a 24 h one) and forces modular arithmetic into every query and every test.
 *
 * With `startMinuteOfDay` + `durationMinutes`, crossing midnight stops being a special
 * case — it is simply a duration that overflows — 24 h becomes expressible, and the
 * duration used for conflict tie-breaking is a field rather than a computation.
 * The UI still renders "23:00 – 07:00". See docs/02, amendment E-01.
 */
data class Schedule(
    val id: ScheduleId = ScheduleId.UNSAVED,
    val uuid: String,
    val profileUuid: String,
    val enabled: Boolean = true,
    /** 0..1439 local wall-clock minute the window opens on. */
    val startMinuteOfDay: Int,
    /** 1..1440. 1440 means a full day. Zero is forbidden: it would loop forever. */
    val durationMinutes: Int,
    val daysMask: Int,
    val label: String? = null,
) {
    val daysOfWeek: Set<DayOfWeek> get() = DayMask.toSet(daysMask)

    val crossesMidnight: Boolean get() = startMinuteOfDay + durationMinutes > MINUTES_PER_DAY

    /** Exclusive end, wrapped into 0..1439. */
    val endMinuteOfDay: Int get() = (startMinuteOfDay + durationMinutes) % MINUTES_PER_DAY

    /** How many calendar days past the start date the window ends on (0, 1 or 2). */
    val endDayOffset: Int get() = (startMinuteOfDay + durationMinutes) / MINUTES_PER_DAY

    fun runsOn(day: DayOfWeek): Boolean = DayMask.has(daysMask, day)

    fun validate(): List<ValidationIssue> = buildList {
        if (!DayMask.isValid(daysMask)) add(ValidationIssue.SCHEDULE_NO_DAYS)
        if (startMinuteOfDay !in 0 until MINUTES_PER_DAY) {
            add(ValidationIssue.SCHEDULE_START_OUT_OF_RANGE)
        }
        if (durationMinutes !in 1..MINUTES_PER_DAY) {
            add(ValidationIssue.SCHEDULE_DURATION_OUT_OF_RANGE)
        }
        if (profileUuid.isBlank()) add(ValidationIssue.SCHEDULE_ORPHANED)
    }

    companion object {
        const val MINUTES_PER_DAY = 1440

        /** Convenience for UI code that still thinks in wall-clock end times. */
        fun fromWallClock(
            uuid: String,
            profileUuid: String,
            startHour: Int,
            startMinute: Int,
            endHour: Int,
            endMinute: Int,
            daysMask: Int,
            enabled: Boolean = true,
            label: String? = null,
        ): Schedule {
            val start = startHour * 60 + startMinute
            val end = endHour * 60 + endMinute
            // end == start is read as a full day, never as a zero-length window.
            val duration = if (end > start) end - start else end - start + MINUTES_PER_DAY
            return Schedule(
                uuid = uuid,
                profileUuid = profileUuid,
                enabled = enabled,
                startMinuteOfDay = start,
                durationMinutes = duration,
                daysMask = daysMask,
                label = label,
            )
        }
    }
}
