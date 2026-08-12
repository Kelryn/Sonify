package com.sonoritmo.core.domain.logic

import com.sonoritmo.core.domain.model.Schedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Which edge of a window a local time is being resolved for. */
enum class EdgeKind { START, END }

/** A concrete, absolute occurrence of a [Schedule] on a given local start date. */
data class Occurrence(
    val schedule: Schedule,
    val startDate: LocalDate,
    val start: Instant,
    val end: Instant,
) {
    /** Half-open: `[start, end)`. A window ending at 07:00 is over at 07:00 sharp. */
    operator fun contains(instant: Instant): Boolean = instant >= start && instant < end
}

/**
 * Turns recurring local-time windows into absolute instants.
 *
 * This is where daylight saving time is dealt with, explicitly, as a product decision
 * rather than as a side effect of whatever `ZonedDateTime.of()` happens to do.
 */
object ScheduleWindows {

    /**
     * A window may last a full 24 h and start as late as 23:59, so it can still be
     * running two calendar days after the date it began on.
     */
    const val DEFAULT_BACK_DAYS = 2L

    /**
     * Resolve a local wall-clock minute on a date to an absolute instant.
     *
     * DST policy — **"prefer coverage"**, so a window is never shorter than the user
     * asked for:
     *
     *  - **Gap** (spring forward, the local time does not exist — e.g. 02:30 on
     *    30 March in `Europe/Madrid`): collapse to the instant of the jump. A start in
     *    the gap begins as the clock jumps; an end in the gap finishes as it jumps.
     *  - **Overlap** (fall back, the local time happens twice — 02:30 on 26 October):
     *    a [EdgeKind.START] takes the **first** occurrence (summer offset) and an
     *    [EdgeKind.END] takes the **second** (winter offset). The window therefore
     *    covers both passes rather than stopping halfway through the first.
     *
     * `ZonedDateTime.of()` does the opposite on overlaps (it picks the earlier offset
     * for both edges), which is why this is written out by hand.
     */
    fun resolveLocal(
        date: LocalDate,
        minuteOfDay: Int,
        zone: ZoneId,
        edge: EdgeKind,
    ): Instant {
        require(minuteOfDay in 0 until Schedule.MINUTES_PER_DAY) {
            "minuteOfDay must be 0..1439, was $minuteOfDay"
        }
        val local = LocalDateTime.of(date, LocalTime.of(minuteOfDay / 60, minuteOfDay % 60))
        val offsets = zone.rules.getValidOffsets(local)
        return when {
            offsets.size == 1 -> local.toInstant(offsets[0])
            offsets.isEmpty() -> zone.rules.getTransition(local).instant
            else -> when (edge) {
                EdgeKind.START -> local.toInstant(offsets[0])
                EdgeKind.END -> local.toInstant(offsets[offsets.size - 1])
            }
        }
    }

    /**
     * The occurrence of [schedule] that *starts* on [startDate], or `null` if the
     * schedule does not run on that weekday.
     *
     * The weekday is always evaluated on the **start** of the window, never on its end.
     * A Saturday 23:00–07:00 window is still running at 01:00 on Sunday, and does *not*
     * start again at 23:00 on Sunday.
     */
    fun occurrenceStartingOn(
        schedule: Schedule,
        startDate: LocalDate,
        zone: ZoneId,
    ): Occurrence? {
        if (!schedule.runsOn(startDate.dayOfWeek)) return null
        val start = resolveLocal(startDate, schedule.startMinuteOfDay, zone, EdgeKind.START)
        val endDate = startDate.plusDays(schedule.endDayOffset.toLong())
        val end = resolveLocal(endDate, schedule.endMinuteOfDay, zone, EdgeKind.END)
        return Occurrence(schedule, startDate, start, end)
    }

    /**
     * Every occurrence of [schedule] whose start date falls in
     * `[referenceDate - backDays, referenceDate + forwardDays]`.
     *
     * [backDays] defaults to 2 because a window may last a full 24 h and start as late
     * as 23:59, so it can still be running two calendar days after it began.
     */
    fun occurrencesAround(
        schedule: Schedule,
        referenceDate: LocalDate,
        zone: ZoneId,
        backDays: Long = DEFAULT_BACK_DAYS,
        forwardDays: Long = 0,
    ): List<Occurrence> = buildList {
        var offset = -backDays
        while (offset <= forwardDays) {
            occurrenceStartingOn(schedule, referenceDate.plusDays(offset), zone)?.let(::add)
            offset++
        }
    }

    /** All occurrences of [schedule] that are running at [instant]. */
    fun occurrencesCovering(
        schedule: Schedule,
        instant: Instant,
        zone: ZoneId,
    ): List<Occurrence> {
        val referenceDate = instant.atZone(zone).toLocalDate()
        return occurrencesAround(schedule, referenceDate, zone).filter { instant in it }
    }
}
