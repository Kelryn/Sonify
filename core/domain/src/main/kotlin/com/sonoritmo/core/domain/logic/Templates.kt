package com.sonoritmo.core.domain.logic

import com.sonoritmo.core.domain.model.CallPolicy
import com.sonoritmo.core.domain.model.DayMask
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.DndSettings
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.ProfileTemplate
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.VolumeSettings
import com.sonoritmo.core.domain.port.TimeSource
import com.sonoritmo.core.domain.port.UuidGenerator

/** A ready-made profile plus the windows that usually go with it. */
data class TemplateResult(
    val profile: SoundProfile,
    val schedules: List<Schedule>,
)

/**
 * The six starting points from RF-07.
 *
 * The point of these is the 30-second promise: nobody should have to reason about seven
 * streams and an interruption filter to get "quiet at night". Names and emoji are
 * resolved in the UI layer from [ProfileTemplate]; nothing here is user-visible text,
 * because strings in code cannot be translated.
 */
object Templates {

    fun build(
        template: ProfileTemplate,
        name: String,
        time: TimeSource,
        uuids: UuidGenerator,
    ): TemplateResult {
        val now = time.now()
        val profileUuid = uuids.newUuid()

        val profile = SoundProfile(
            uuid = profileUuid,
            name = name,
            emoji = emojiFor(template),
            colorSeed = colorFor(template),
            priority = priorityFor(template),
            templateKey = template,
            volumes = volumesFor(template),
            ringerMode = ringerFor(template),
            dnd = dndFor(template),
            options = optionsFor(template),
            createdAt = now,
            updatedAt = now,
        ).normalized()

        return TemplateResult(profile, schedulesFor(template, profileUuid, uuids))
    }

    private fun emojiFor(template: ProfileTemplate): String = when (template) {
        ProfileTemplate.NIGHT -> "🌙"
        ProfileTemplate.WORK -> "💼"
        ProfileTemplate.MEETING -> "🤝"
        ProfileTemplate.CINEMA -> "🎬"
        ProfileTemplate.DRIVING -> "🚗"
        ProfileTemplate.WEEKEND -> "🌿"
    }

    private fun colorFor(template: ProfileTemplate): Int = when (template) {
        ProfileTemplate.NIGHT -> 0xFF3B4A6B.toInt()
        ProfileTemplate.WORK -> 0xFF4A6FA5.toInt()
        ProfileTemplate.MEETING -> 0xFF7A5C9E.toInt()
        ProfileTemplate.CINEMA -> 0xFF2E3440.toInt()
        ProfileTemplate.DRIVING -> 0xFF2E7D6B.toInt()
        ProfileTemplate.WEEKEND -> 0xFF6E8B3D.toInt()
    }

    /**
     * Higher priority for windows the user sets by hand for a specific event, so a
     * "meeting" beats the standing "work" window without either being reordered.
     */
    private fun priorityFor(template: ProfileTemplate): Int = when (template) {
        ProfileTemplate.MEETING -> 80
        ProfileTemplate.CINEMA -> 80
        ProfileTemplate.DRIVING -> 70
        ProfileTemplate.NIGHT -> 60
        ProfileTemplate.WORK -> 50
        ProfileTemplate.WEEKEND -> 40
    }

    private fun volumesFor(template: ProfileTemplate): VolumeSettings = when (template) {
        // The alarm stays at 100. This is the single most important line in the file:
        // silencing the alarm along with everything else is the failure users of the
        // competition report as "the app made me miss work".
        ProfileTemplate.NIGHT -> VolumeSettings(
            music = 20,
            alarm = 100,
            notification = 0,
            system = 0,
        )
        ProfileTemplate.WORK -> VolumeSettings(
            ring = 0,
            notification = 0,
            music = 40,
            alarm = 100,
            system = 0,
        )
        ProfileTemplate.MEETING -> VolumeSettings(
            music = 0,
            alarm = 80,
            notification = 0,
            system = 0,
        )
        ProfileTemplate.CINEMA -> VolumeSettings(
            music = 0,
            alarm = 60,
            notification = 0,
            system = 0,
        )
        ProfileTemplate.DRIVING -> VolumeSettings(
            ring = 90,
            notification = 60,
            music = 70,
            alarm = 100,
        )
        ProfileTemplate.WEEKEND -> VolumeSettings(
            ring = 80,
            notification = 70,
            music = 70,
            alarm = 100,
        )
    }

    private fun ringerFor(template: ProfileTemplate): RingerMode = when (template) {
        ProfileTemplate.NIGHT -> RingerMode.SILENT
        ProfileTemplate.WORK -> RingerMode.VIBRATE
        ProfileTemplate.MEETING -> RingerMode.VIBRATE
        ProfileTemplate.CINEMA -> RingerMode.SILENT
        ProfileTemplate.DRIVING -> RingerMode.NORMAL
        ProfileTemplate.WEEKEND -> RingerMode.NORMAL
    }

    private fun dndFor(template: ProfileTemplate): DndSettings = when (template) {
        ProfileTemplate.NIGHT -> DndSettings(
            mode = DndMode.PRIORITY,
            allowCalls = CallPolicy.STARRED,
            allowRepeatCallers = true,
            allowAlarms = true,
            allowMedia = true,
        )
        ProfileTemplate.MEETING -> DndSettings(
            mode = DndMode.PRIORITY,
            allowCalls = CallPolicy.STARRED,
            allowRepeatCallers = true,
        )
        ProfileTemplate.CINEMA -> DndSettings(
            mode = DndMode.TOTAL_SILENCE,
            allowCalls = CallPolicy.NONE,
            allowRepeatCallers = false,
            allowAlarms = true,
        )
        ProfileTemplate.WORK -> DndSettings(
            mode = DndMode.PRIORITY,
            allowCalls = CallPolicy.CONTACTS,
            allowRepeatCallers = true,
        )
        ProfileTemplate.DRIVING -> DndSettings.UNCHANGED
        ProfileTemplate.WEEKEND -> DndSettings(mode = DndMode.RELEASE)
    }

    private fun optionsFor(template: ProfileTemplate): ProfileOptions = when (template) {
        ProfileTemplate.NIGHT -> ProfileOptions(
            restoreOnExit = true,
            transitionSeconds = 5,
            skipDuringCall = true,
            skipIfMediaPlaying = true,
        )
        ProfileTemplate.CINEMA, ProfileTemplate.MEETING -> ProfileOptions(
            restoreOnExit = true,
            transitionSeconds = 0,
            skipDuringCall = true,
        )
        else -> ProfileOptions.DEFAULT
    }

    private fun schedulesFor(
        template: ProfileTemplate,
        profileUuid: String,
        uuids: UuidGenerator,
    ): List<Schedule> = when (template) {
        ProfileTemplate.NIGHT -> listOf(
            Schedule.fromWallClock(
                uuid = uuids.newUuid(),
                profileUuid = profileUuid,
                startHour = 23, startMinute = 0,
                endHour = 7, endMinute = 0,
                daysMask = DayMask.ALL,
            ),
        )
        ProfileTemplate.WORK -> listOf(
            Schedule.fromWallClock(
                uuid = uuids.newUuid(),
                profileUuid = profileUuid,
                startHour = 9, startMinute = 0,
                endHour = 18, endMinute = 0,
                daysMask = DayMask.WEEKDAYS,
            ),
        )
        ProfileTemplate.WEEKEND -> listOf(
            Schedule.fromWallClock(
                uuid = uuids.newUuid(),
                profileUuid = profileUuid,
                startHour = 9, startMinute = 0,
                endHour = 23, endMinute = 0,
                daysMask = DayMask.WEEKEND,
            ),
        )
        // Meeting, cinema and driving are activated by hand, with a duration.
        ProfileTemplate.MEETING, ProfileTemplate.CINEMA, ProfileTemplate.DRIVING -> emptyList()
    }
}
