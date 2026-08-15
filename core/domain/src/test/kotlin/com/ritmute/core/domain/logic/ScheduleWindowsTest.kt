package com.ritmute.core.domain.logic

import com.google.common.truth.Truth.assertThat
import com.ritmute.core.domain.MADRID
import com.ritmute.core.domain.SANTIAGO
import com.ritmute.core.domain.localInstant
import com.ritmute.core.domain.model.DayMask
import com.ritmute.core.domain.schedule
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import org.junit.Test

/**
 * Cases 1–16 of the critical list: midnight crossing, weekday semantics, DST gaps and
 * overlaps, and time-zone changes.
 */
class ScheduleWindowsTest {

    // ── Midnight crossing and weekday semantics ─────────────────────────────

    /** Case 1: the weekday is evaluated on the START of the window, never on its end. */
    @Test
    fun `saturday night window is still active on sunday morning`() {
        val saturdayNight = schedule("s1", "p1", "23:00", "07:00", DayMask.of(DayOfWeek.SATURDAY))

        // 2026-08-15 is a Saturday, 2026-08-16 a Sunday.
        val sundayOneAm = localInstant("2026-08-16", "01:00")
        val covering = ScheduleWindows.occurrencesCovering(saturdayNight, sundayOneAm, MADRID)

        assertThat(covering).hasSize(1)
        assertThat(covering.single().startDate).isEqualTo(LocalDate.parse("2026-08-15"))
    }

    /** Case 1b: and it does NOT start again on Sunday night. */
    @Test
    fun `saturday night window does not start on sunday night`() {
        val saturdayNight = schedule("s1", "p1", "23:00", "07:00", DayMask.of(DayOfWeek.SATURDAY))
        val sundayElevenPm = localInstant("2026-08-16", "23:30")

        assertThat(ScheduleWindows.occurrencesCovering(saturdayNight, sundayElevenPm, MADRID)).isEmpty()
    }

    /** Case 2: the window is half-open — closed on the left, open on the right. */
    @Test
    fun `window boundary is half open`() {
        val nightly = schedule("s1", "p1", "23:00", "07:00")

        assertThat(ScheduleWindows.occurrencesCovering(nightly, localInstant("2026-08-16", "06:59"), MADRID))
            .hasSize(1)
        assertThat(ScheduleWindows.occurrencesCovering(nightly, localInstant("2026-08-16", "07:00"), MADRID))
            .isEmpty()
        assertThat(ScheduleWindows.occurrencesCovering(nightly, localInstant("2026-08-15", "23:00"), MADRID))
            .hasSize(1)
    }

    /** Case 3: start == end means a full day, never a zero-length window. */
    @Test
    fun `equal start and end means twenty four hours`() {
        val allDay = schedule("s1", "p1", "09:00", "09:00")

        assertThat(allDay.durationMinutes).isEqualTo(1440)
        assertThat(allDay.crossesMidnight).isTrue()
        assertThat(ScheduleWindows.occurrencesCovering(allDay, localInstant("2026-08-15", "03:00"), MADRID))
            .isNotEmpty()
    }

    /** Case 4: a 00:00 → 00:00 Monday window covers exactly Monday. */
    @Test
    fun `midnight to midnight covers exactly one day`() {
        val monday = schedule("s1", "p1", "00:00", "00:00", DayMask.of(DayOfWeek.MONDAY))
        // 2026-08-17 is a Monday.
        val occurrence = ScheduleWindows.occurrenceStartingOn(monday, LocalDate.parse("2026-08-17"), MADRID)

        requireNotNull(occurrence)
        assertThat(occurrence.start).isEqualTo(localInstant("2026-08-17", "00:00"))
        assertThat(occurrence.end).isEqualTo(localInstant("2026-08-18", "00:00"))
    }

    /** Case 5: back-to-back daily windows produce one shared edge, not a duplicate. */
    @Test
    fun `daily midnight crossing window chains without gaps`() {
        val nightly = schedule("s1", "p1", "23:00", "23:00")
        val instant = localInstant("2026-08-16", "12:00")

        val covering = ScheduleWindows.occurrencesCovering(nightly, instant, MADRID)
        assertThat(covering).hasSize(1)
    }

    // ── Daylight saving time: Europe/Madrid ─────────────────────────────────

    /**
     * Case 7 (gap). On 2026-03-29 Madrid jumps 02:00 → 03:00, so 02:30 does not exist.
     * A start in the gap collapses to the instant of the jump.
     */
    @Test
    fun `start inside a spring forward gap collapses to the jump`() {
        val resolved = ScheduleWindows.resolveLocal(
            date = LocalDate.parse("2026-03-29"),
            minuteOfDay = 2 * 60 + 30,
            zone = MADRID,
            edge = EdgeKind.START,
        )
        // The jump happens at 01:00 UTC.
        assertThat(resolved).isEqualTo(java.time.Instant.parse("2026-03-29T01:00:00Z"))
    }

    /** Case 8 (gap, end edge). Same collapse, so the window ends as the clock jumps. */
    @Test
    fun `end inside a spring forward gap collapses to the jump`() {
        val resolved = ScheduleWindows.resolveLocal(
            date = LocalDate.parse("2026-03-29"),
            minuteOfDay = 2 * 60 + 30,
            zone = MADRID,
            edge = EdgeKind.END,
        )
        assertThat(resolved).isEqualTo(java.time.Instant.parse("2026-03-29T01:00:00Z"))
    }

    /**
     * Case 9 (overlap, start edge). On 2026-10-25 Madrid falls back 03:00 → 02:00, so
     * 02:30 happens twice. "Prefer coverage" means a START takes the FIRST occurrence.
     */
    @Test
    fun `start inside a fall back overlap takes the first occurrence`() {
        val resolved = ScheduleWindows.resolveLocal(
            date = LocalDate.parse("2026-10-25"),
            minuteOfDay = 2 * 60 + 30,
            zone = MADRID,
            edge = EdgeKind.START,
        )
        // First pass is CEST (UTC+2) → 00:30 UTC.
        assertThat(resolved).isEqualTo(java.time.Instant.parse("2026-10-25T00:30:00Z"))
    }

    /** Case 10 (overlap, end edge): an END takes the SECOND occurrence. */
    @Test
    fun `end inside a fall back overlap takes the second occurrence`() {
        val resolved = ScheduleWindows.resolveLocal(
            date = LocalDate.parse("2026-10-25"),
            minuteOfDay = 2 * 60 + 30,
            zone = MADRID,
            edge = EdgeKind.END,
        )
        // Second pass is CET (UTC+1) → 01:30 UTC.
        assertThat(resolved).isEqualTo(java.time.Instant.parse("2026-10-25T01:30:00Z"))
    }

    /** Case 11: a 23:00–07:00 window across the spring jump really lasts 7 hours. */
    @Test
    fun `night window across spring forward lasts seven hours`() {
        val nightly = schedule("s1", "p1", "23:00", "07:00")
        val occurrence = ScheduleWindows.occurrenceStartingOn(nightly, LocalDate.parse("2026-03-28"), MADRID)

        requireNotNull(occurrence)
        assertThat(Duration.between(occurrence.start, occurrence.end)).isEqualTo(Duration.ofHours(7))
    }

    /** Case 12: and nine hours across the autumn one. */
    @Test
    fun `night window across fall back lasts nine hours`() {
        val nightly = schedule("s1", "p1", "23:00", "07:00")
        val occurrence = ScheduleWindows.occurrenceStartingOn(nightly, LocalDate.parse("2026-10-24"), MADRID)

        requireNotNull(occurrence)
        assertThat(Duration.between(occurrence.start, occurrence.end)).isEqualTo(Duration.ofHours(9))
    }

    /** Southern-hemisphere rule, to make sure nothing is hard-coded to the north. */
    @Test
    fun `southern hemisphere dst is handled`() {
        val nightly = schedule("s1", "p1", "23:00", "07:00")
        // Santiago moves its clock in September (spring in the south).
        val occurrence = ScheduleWindows.occurrenceStartingOn(nightly, LocalDate.parse("2026-09-05"), SANTIAGO)

        requireNotNull(occurrence)
        assertThat(Duration.between(occurrence.start, occurrence.end)).isAtMost(Duration.ofHours(9))
        assertThat(Duration.between(occurrence.start, occurrence.end)).isAtLeast(Duration.ofHours(7))
    }

    // ── Time zone ───────────────────────────────────────────────────────────

    /** Case 16: the zone is a parameter, never cached — two zones give two answers. */
    @Test
    fun `the same wall clock resolves differently in different zones`() {
        val date = LocalDate.parse("2026-08-15")
        val madrid = ScheduleWindows.resolveLocal(date, 23 * 60, MADRID, EdgeKind.START)
        val santiago = ScheduleWindows.resolveLocal(date, 23 * 60, SANTIAGO, EdgeKind.START)

        assertThat(madrid).isNotEqualTo(santiago)
    }

    @Test
    fun `minute of day outside range is rejected`() {
        val failure = runCatching {
            ScheduleWindows.resolveLocal(LocalDate.parse("2026-08-15"), 1440, MADRID, EdgeKind.START)
        }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `occurrence returns null when the schedule does not run that weekday`() {
        val weekdays = schedule("s1", "p1", "09:00", "18:00", DayMask.WEEKDAYS)
        // 2026-08-16 is a Sunday.
        assertThat(ScheduleWindows.occurrenceStartingOn(weekdays, LocalDate.parse("2026-08-16"), MADRID))
            .isNull()
    }
}
