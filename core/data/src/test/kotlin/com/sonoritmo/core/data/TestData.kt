package com.sonoritmo.core.data

import com.sonoritmo.core.data.backup.SuppressedEffects
import com.sonoritmo.core.data.preferences.SchedulerHealth
import com.sonoritmo.core.data.preferences.ThemeMode
import com.sonoritmo.core.data.preferences.UserSettings
import com.sonoritmo.core.domain.model.CallPolicy
import com.sonoritmo.core.domain.model.ConversationPolicy
import com.sonoritmo.core.domain.model.DayMask
import com.sonoritmo.core.domain.model.DndMode
import com.sonoritmo.core.domain.model.DndSettings
import com.sonoritmo.core.domain.model.MessagePolicy
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.ProfileTemplate
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.VolumeSettings
import java.time.DayOfWeek
import java.time.Instant

/**
 * Fixtures for the JVM tests of this module.
 *
 * Every profile here is already `normalized()` and passes `validate()`. That is deliberate:
 * the import path normalises and validates, so a fixture that did not would make a
 * round-trip assertion fail for a reason that has nothing to do with the format.
 */
object TestData {

    val CREATED: Instant = Instant.parse("2026-07-02T18:22:11Z")
    val UPDATED: Instant = Instant.parse("2026-08-01T09:03:40Z")
    val EXPORTED_AT: Instant = Instant.parse("2026-08-12T21:14:03Z")

    const val NIGHT_UUID = "3a6f0f92-8c41-4f5e-b0d2-7c9a1e5f3b21"
    const val WORK_UUID = "9f1c2b7e-4d3a-4c19-9a51-0b7e6d2f1a44"
    const val MINIMAL_UUID = "0000aaaa-1111-4bbb-8ccc-ddddeeeeffff"

    /**
     * Every field set to something other than its default, including a `null` volume next
     * to a zero volume — the two cases the "null means do not touch" convention is most
     * likely to confuse.
     */
    fun nightProfile(): SoundProfile = SoundProfile(
        uuid = NIGHT_UUID,
        name = "Noche",
        emoji = "🌙",
        colorSeed = 0xFF3B4A6B.toInt(),
        enabled = true,
        priority = 80,
        sortOrder = 0,
        templateKey = ProfileTemplate.NIGHT,
        volumes = VolumeSettings(
            ring = null,
            notification = 0,
            music = 20,
            alarm = 100,
            system = 0,
            voiceCall = null,
        ),
        // SILENT silences the ring, so volumes.ring stays null: that is the normalised
        // form of "silent night", and the importer must reproduce it exactly.
        ringerMode = RingerMode.SILENT,
        dnd = DndSettings(
            mode = DndMode.PRIORITY,
            allowCalls = CallPolicy.STARRED,
            allowRepeatCallers = true,
            allowMessages = MessagePolicy.NONE,
            allowConversations = ConversationPolicy.NONE,
            allowAlarms = true,
            allowMedia = true,
            allowReminders = false,
            allowEvents = false,
            suppressedVisualEffects = SuppressedEffects.fromNames(
                listOf("SCREEN_ON", "PEEK", "AMBIENT"),
            ).mask,
        ),
        options = ProfileOptions(
            restoreOnExit = true,
            transitionSeconds = 5,
            skipDuringCall = true,
            skipIfMediaPlaying = true,
            notifyOnApply = false,
        ),
        // Present in the domain object, and expected to be absent from every export.
        zenRuleId = "zen-rule-42",
        createdAt = CREATED,
        updatedAt = UPDATED,
    )

    /** Uses defaults almost everywhere, and a ringer mode of NORMAL with a non-zero ring. */
    fun workProfile(): SoundProfile = SoundProfile(
        uuid = WORK_UUID,
        name = "Trabajo",
        emoji = "💼",
        colorSeed = 0xFF4A6FA5.toInt(),
        priority = 50,
        sortOrder = 1,
        templateKey = ProfileTemplate.WORK,
        volumes = VolumeSettings(ring = 35, notification = 35, alarm = 100, system = 10),
        ringerMode = RingerMode.NORMAL,
        dnd = DndSettings(
            mode = null,
            allowCalls = CallPolicy.ANY,
            allowMessages = MessagePolicy.CONTACTS,
            allowConversations = ConversationPolicy.IMPORTANT,
            allowReminders = true,
            allowEvents = true,
        ),
        options = ProfileOptions(restoreOnExit = false),
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    /**
     * The smallest thing that is still a valid profile: one stream and nothing else.
     *
     * There is no "everything null" fixture on purpose — the domain rejects a profile that
     * changes nothing, so such a file is expected to be *rejected*, and that is tested
     * separately rather than round-tripped.
     */
    fun minimalProfile(): SoundProfile = SoundProfile(
        uuid = MINIMAL_UUID,
        name = "Mínimo",
        volumes = VolumeSettings(music = 50),
        sortOrder = 2,
        createdAt = CREATED,
        updatedAt = CREATED,
    )

    /** 23:00 → 07:00 every day: crosses midnight. */
    fun crossingMidnight(): Schedule = Schedule(
        uuid = "b21d7c04-5f8e-42a7-9c31-1de4a70f8c55",
        profileUuid = NIGHT_UUID,
        enabled = true,
        startMinuteOfDay = 23 * 60,
        durationMinutes = 8 * 60,
        daysMask = DayMask.ALL,
        label = "Entre semana",
    )

    /** A full 24 hours, which the old start/end model could not express at all. */
    fun fullDay(): Schedule = Schedule(
        uuid = "c7e01a3d-9b44-4f10-8e6b-2af5c8d31097",
        profileUuid = NIGHT_UUID,
        enabled = false,
        startMinuteOfDay = 0,
        durationMinutes = Schedule.MINUTES_PER_DAY,
        daysMask = DayMask.of(DayOfWeek.SATURDAY),
        label = null,
    )

    fun weekdayWindow(): Schedule = Schedule(
        uuid = "6d0b8f11-3c27-4b93-a0f6-58e2c19d7b40",
        profileUuid = WORK_UUID,
        startMinuteOfDay = 9 * 60,
        durationMinutes = 9 * 60,
        daysMask = DayMask.WEEKDAYS,
    )

    fun settings(): UserSettings = UserSettings(
        onboardingCompleted = true,
        themeMode = ThemeMode.DARK,
        dynamicColor = false,
        languageTag = "es-ES",
        maxReliabilityMode = true,
        defaultProfileUuid = WORK_UUID,
        schedulerHealth = SchedulerHealth.HEALTHY,
        schedulerHealthCheckedAt = UPDATED,
    )

    fun allProfiles(): List<SoundProfile> = listOf(nightProfile(), workProfile(), minimalProfile())

    fun allSchedules(): List<Schedule> = listOf(crossingMidnight(), fullDay(), weekdayWindow())
}
