package com.sonoritmo.core.ui.format

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.RingVolume
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.DayMask
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.ui.R
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Turns domain values into text the user reads.
 *
 * Deliberately in the UI layer and deliberately resource-backed. Nothing here builds a
 * sentence out of literals in code: the log, the widget, the tile and the schedule list
 * all describe the same things, and they have to say them the same way in both languages.
 *
 * Times use the user's locale and their 12/24-hour preference via
 * [DateTimeFormatter.ofLocalizedTime]; hard-coding `HH:mm` would look wrong to anyone on a
 * 12-hour locale.
 */
object ScheduleFormatter {

    fun time(minuteOfDay: Int, locale: Locale = Locale.getDefault()): String =
        LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))

    @Composable
    fun range(schedule: Schedule): String {
        val start = time(schedule.startMinuteOfDay)
        val end = time(schedule.endMinuteOfDay)
        val endText = if (schedule.crossesMidnight) {
            stringResource(R.string.ui_schedule_crosses_midnight, end)
        } else {
            end
        }
        return stringResource(R.string.ui_schedule_range, start, endText)
    }

    @Composable
    fun days(daysMask: Int, locale: Locale = Locale.getDefault()): String = when (daysMask) {
        DayMask.ALL -> stringResource(R.string.ui_days_every_day)
        DayMask.WEEKDAYS -> stringResource(R.string.ui_days_weekdays)
        DayMask.WEEKEND -> stringResource(R.string.ui_days_weekend)
        else -> {
            val separator = stringResource(R.string.ui_day_separator)
            val first = java.time.temporal.WeekFields.of(locale).firstDayOfWeek
            (0..6)
                .map { first.plus(it.toLong()) }
                .filter { DayMask.has(daysMask, it) }
                .joinToString(separator) {
                    it.getDisplayName(java.time.format.TextStyle.SHORT_STANDALONE, locale)
                }
        }
    }

    @Composable
    fun full(schedule: Schedule): String =
        stringResource(R.string.ui_schedule_full, days(schedule.daysMask), range(schedule))

    @Composable
    fun duration(minutes: Int): String {
        val hours = minutes / 60
        val rest = minutes % 60
        return when {
            hours > 0 && rest > 0 -> stringResource(R.string.ui_duration_hours_minutes, hours, rest)
            hours > 0 -> stringResource(R.string.ui_duration_hours, hours)
            else -> stringResource(R.string.ui_duration_minutes, rest)
        }
    }

    @Composable
    fun streamLabel(stream: AudioStream): String = stringResource(
        when (stream) {
            AudioStream.RING -> R.string.ui_stream_ring
            AudioStream.NOTIFICATION -> R.string.ui_stream_notification
            AudioStream.MUSIC -> R.string.ui_stream_music
            AudioStream.ALARM -> R.string.ui_stream_alarm
            AudioStream.SYSTEM -> R.string.ui_stream_system
            AudioStream.VOICE_CALL -> R.string.ui_stream_voice_call
            AudioStream.ACCESSIBILITY -> R.string.ui_stream_accessibility
        },
    )

    @Composable
    fun ringerLabel(mode: RingerMode): String = stringResource(
        when (mode) {
            RingerMode.NORMAL -> R.string.ui_ringer_normal
            RingerMode.VIBRATE -> R.string.ui_ringer_vibrate
            RingerMode.SILENT -> R.string.ui_ringer_silent
        },
    )

    @Composable
    fun volumeLabel(percent: Int?): String =
        if (percent == null) {
            stringResource(R.string.ui_volume_untouched)
        } else {
            stringResource(R.string.ui_volume_percent, percent)
        }

    fun streamIcon(stream: AudioStream): ImageVector = when (stream) {
        AudioStream.RING -> Icons.Filled.RingVolume
        AudioStream.NOTIFICATION -> Icons.Filled.Notifications
        AudioStream.MUSIC -> Icons.Filled.MusicNote
        AudioStream.ALARM -> Icons.Filled.Alarm
        AudioStream.SYSTEM -> Icons.Filled.Campaign
        AudioStream.VOICE_CALL -> Icons.Filled.Call
        AudioStream.ACCESSIBILITY -> Icons.Filled.Accessibility
    }
}
