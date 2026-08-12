package com.sonoritmo.core.data.backup

import kotlinx.serialization.Serializable

/**
 * The on-disk shape of an exported configuration (RF-36 / RF-37, CU-08).
 *
 * ## Decisions of form, and why
 *
 *  - **Schedules are nested inside their profile**, not a flat list with a `profileUuid`
 *    on each. That makes an orphaned window structurally impossible in the file, and
 *    "import only the Night profile" becomes a filter over one array.
 *  - **No row ids anywhere.** Identity in the file is the uuid, and only the uuid. A
 *    rowid means something different on every device, and the day it leaks into the file
 *    is the day two phones with the same configuration start behaving differently.
 *  - **[schemaVersion] is independent of both the database version and the app version.**
 *    Merging them is the classic mistake: the database changes for internal reasons that
 *    have no effect on the file.
 *  - **Nulls are written explicitly.** The file is meant to be read by humans, and an
 *    explicit `"ring": null` documents "do not touch this stream" without anyone having
 *    to look anything up. It also makes the round trip byte-identical, which is what the
 *    exit criterion for this phase is tested against.
 *  - **Not exported:** the activity log, the snapshots, the automation state and
 *    `zenRuleId`. All of them are local device state; exporting the zen rule id in
 *    particular would leave two phones believing they own the same system Mode.
 */
@Serializable
data class BackupFile(
    val schemaVersion: Int,
    /** ISO-8601 instant, e.g. `2026-08-12T21:14:03Z`. */
    val exportedAt: String,
    val generator: GeneratorDto,
    val settings: SettingsDto? = null,
    val profiles: List<ProfileDto> = emptyList(),
    val integrity: IntegrityDto? = null,
)

@Serializable
data class GeneratorDto(
    val app: String,
    val versionName: String? = null,
    val versionCode: Long? = null,
)

/**
 * The subset of preferences that is worth carrying to another phone.
 *
 * Onboarding completion and the cached scheduler health are pointedly absent: they
 * describe *this* installation on *this* device, and importing them would tell the new
 * phone that a setup it never ran is finished.
 */
@Serializable
data class SettingsDto(
    val themeMode: String,
    val dynamicColor: Boolean,
    val languageTag: String? = null,
    val maxReliabilityMode: Boolean,
    val defaultProfileUuid: String? = null,
)

/**
 * Counters and a checksum.
 *
 * The counters catch a truncated file, which is the realistic corruption. The hash is
 * advisory only: a mismatch is reported but never blocks the import, because editing the
 * JSON by hand is a legitimate thing to do with a GPLv3 app and refusing to read an
 * edited file would be hostile.
 */
@Serializable
data class IntegrityDto(
    val profileCount: Int,
    val scheduleCount: Int,
    val sha256: String,
)

@Serializable
data class ProfileDto(
    val uuid: String,
    val name: String,
    val emoji: String? = null,
    /**
     * ARGB as an unsigned 32-bit number, e.g. `4283215696`.
     *
     * Written unsigned rather than as the signed `Int` the platform uses, because a
     * negative colour in a file meant for humans is a puzzle with no upside.
     */
    val colorSeed: Long,
    val enabled: Boolean,
    val priority: Int,
    val sortOrder: Int,
    val templateKey: String? = null,
    val createdAt: String,
    val updatedAt: String,
    val volumes: VolumesDto,
    val ringerMode: String? = null,
    val dnd: DndDto,
    val options: OptionsDto,
    val schedules: List<ScheduleDto> = emptyList(),
)

/** Percentages, 0..100. `null` means "do not touch this stream". */
@Serializable
data class VolumesDto(
    val ring: Int? = null,
    val notification: Int? = null,
    val music: Int? = null,
    val alarm: Int? = null,
    val system: Int? = null,
    val voiceCall: Int? = null,
)

@Serializable
data class DndDto(
    val mode: String? = null,
    val allowCalls: String,
    val allowRepeatCallers: Boolean,
    val allowMessages: String,
    val allowConversations: String,
    val allowAlarms: Boolean,
    val allowMedia: Boolean,
    val allowReminders: Boolean,
    val allowEvents: Boolean,
    /**
     * Effect names, not the integer bitmask.
     *
     * The bitmask is a detail of one platform's API, and this file is meant to outlive
     * platform versions. Names also survive the day a bit is renumbered.
     */
    val suppressedVisualEffects: List<String> = emptyList(),
)

@Serializable
data class OptionsDto(
    val restoreOnExit: Boolean,
    val transitionSeconds: Int,
    val skipDuringCall: Boolean,
    val skipIfMediaPlaying: Boolean,
    val notifyOnApply: Boolean,
)

@Serializable
data class ScheduleDto(
    val uuid: String,
    val enabled: Boolean,
    val label: String? = null,
    /**
     * Local wall-clock start as `"HH:mm"`.
     *
     * Readable on purpose, even though the database stores a minute-of-day integer. The
     * file is for people; the integer is for SQL.
     */
    val startTime: String,
    /**
     * 1..1440. Paired with [startTime] rather than an end time so that
     * `"23:30" + 480` needs no footnote about midnight, and so a full day is
     * expressible at all.
     */
    val durationMinutes: Int,
    /** ISO day names, Monday first. */
    val daysOfWeek: List<String> = emptyList(),
)
