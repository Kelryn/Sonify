package com.sonoritmo.core.data.backup

import com.sonoritmo.core.data.mapper.EnumCodecs
import com.sonoritmo.core.data.preferences.UserSettings
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** Identifies the build that wrote a file, for support and for upgrade decisions. */
data class ExporterIdentity(
    val app: String = "SonoRitmo",
    val versionName: String? = null,
    val versionCode: Long? = null,
)

data class ExportSummary(
    val json: String,
    val profileCount: Int,
    val scheduleCount: Int,
) {
    val bytes: Int get() = json.toByteArray().size
}

/**
 * Turns a configuration into the backup file. Pure: no database, no clock, no Android.
 *
 * That is not tidiness for its own sake — the exit criterion for this phase is "export and
 * import round-trip with no loss, verified by test", and a pure function is the only
 * version of this code that such a test can exercise without an emulator.
 */
object BackupWriter {

    fun serialize(
        profiles: List<SoundProfile>,
        schedules: List<Schedule>,
        settings: UserSettings?,
        exportedAt: Instant,
        identity: ExporterIdentity = ExporterIdentity(),
    ): ExportSummary {
        val byProfile = schedules.groupBy { it.profileUuid }

        // Sorted by the user's own order, then by uuid. Never by row id, and never by
        // whatever order the database happened to return: two exports of the same
        // configuration have to produce the same bytes, or the round-trip test proves
        // nothing about the format.
        val profileDtos = profiles
            .sortedWith(compareBy({ it.sortOrder }, { it.uuid }))
            .map { profile ->
                profile.toDto(byProfile[profile.uuid].orEmpty().sortedBy { it.uuid })
            }

        val scheduleCount = profileDtos.sumOf { it.schedules.size }

        val withoutIntegrity = BackupFile(
            schemaVersion = BackupFormat.CURRENT_VERSION,
            exportedAt = BackupFormat.formatInstant(exportedAt),
            generator = GeneratorDto(
                app = identity.app,
                versionName = identity.versionName,
                versionCode = identity.versionCode,
            ),
            settings = settings?.toDto(),
            profiles = profileDtos,
            integrity = null,
        )

        // The checksum is taken over the document as it will be written, minus the
        // integrity block itself, so that a reader can recompute it from the file alone.
        val root = BackupFormat.json.encodeToJsonElement(
            BackupFile.serializer(),
            withoutIntegrity,
        ) as JsonObject

        val complete = withoutIntegrity.copy(
            integrity = IntegrityDto(
                profileCount = profileDtos.size,
                scheduleCount = scheduleCount,
                sha256 = BackupFormat.checksum(root),
            ),
        )

        return ExportSummary(
            json = BackupFormat.json.encodeToString(BackupFile.serializer(), complete),
            profileCount = profileDtos.size,
            scheduleCount = scheduleCount,
        )
    }
}

// ── Domain to DTO ────────────────────────────────────────────────────────────

private fun SoundProfile.toDto(schedules: List<Schedule>): ProfileDto = ProfileDto(
    uuid = uuid,
    name = name,
    emoji = emoji,
    colorSeed = BackupFormat.colorToUnsigned(colorSeed),
    enabled = enabled,
    priority = priority,
    sortOrder = sortOrder,
    templateKey = templateKey?.let { EnumCodecs.code(it) },
    createdAt = BackupFormat.formatInstant(createdAt),
    updatedAt = BackupFormat.formatInstant(updatedAt),
    volumes = VolumesDto(
        ring = volumes.ring,
        notification = volumes.notification,
        music = volumes.music,
        alarm = volumes.alarm,
        system = volumes.system,
        voiceCall = volumes.voiceCall,
    ),
    ringerMode = ringerMode?.let { EnumCodecs.code(it) },
    dnd = DndDto(
        mode = dnd.mode?.let { EnumCodecs.code(it) },
        allowCalls = EnumCodecs.code(dnd.allowCalls),
        allowRepeatCallers = dnd.allowRepeatCallers,
        allowMessages = EnumCodecs.code(dnd.allowMessages),
        allowConversations = EnumCodecs.code(dnd.allowConversations),
        allowAlarms = dnd.allowAlarms,
        allowMedia = dnd.allowMedia,
        allowReminders = dnd.allowReminders,
        allowEvents = dnd.allowEvents,
        suppressedVisualEffects = SuppressedEffects.toNames(dnd.suppressedVisualEffects),
    ),
    options = OptionsDto(
        restoreOnExit = options.restoreOnExit,
        transitionSeconds = options.transitionSeconds,
        skipDuringCall = options.skipDuringCall,
        skipIfMediaPlaying = options.skipIfMediaPlaying,
        notifyOnApply = options.notifyOnApply,
    ),
    // zenRuleId is absent on purpose: it names a rule in *this* device's system settings,
    // and two installations believing they own the same one is a defect whose damage is
    // visible outside the app — an orphaned Mode the user cannot remove.
    schedules = schedules.map { it.toDto() },
)

private fun Schedule.toDto(): ScheduleDto = ScheduleDto(
    uuid = uuid,
    enabled = enabled,
    label = label,
    startTime = BackupFormat.formatStartTime(startMinuteOfDay),
    durationMinutes = durationMinutes,
    daysOfWeek = EnumCodecs.daysMaskToCodes(daysMask),
)

private fun UserSettings.toDto(): SettingsDto = SettingsDto(
    themeMode = themeMode.name,
    dynamicColor = dynamicColor,
    languageTag = languageTag,
    maxReliabilityMode = maxReliabilityMode,
    defaultProfileUuid = defaultProfileUuid,
)
