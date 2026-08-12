package com.sonoritmo.core.data.backup

import androidx.room.withTransaction
import com.sonoritmo.core.data.database.SonoRitmoDatabase
import com.sonoritmo.core.data.mapper.toEntity
import com.sonoritmo.core.data.preferences.ThemeMode
import com.sonoritmo.core.data.preferences.UserPreferences
import com.sonoritmo.core.domain.model.ProfileId
import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.ScheduleId
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.port.UuidGenerator
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Applies a backup file to the device.
 *
 * ## Why inspection and application are two separate calls
 *
 * [inspect] delegates to [BackupReader] and touches nothing. Importing blind, when one of
 * the modes deletes everything the user has, is not acceptable: CU-08 is where this app
 * either earns or loses a user's trust, so the UI gets to show them exactly what is about
 * to happen, and only then does [apply] run.
 */
@Singleton
class BackupImporter @Inject constructor(
    private val database: SonoRitmoDatabase,
    private val userPreferences: UserPreferences,
    private val uuidGenerator: UuidGenerator,
) {

    /** Validate without writing. Safe to call on whatever file the user just picked. */
    fun inspect(raw: String): BackupInspection = BackupReader.inspect(raw)

    fun inspect(source: InputStream): BackupInspection = BackupReader.inspect(source)

    suspend fun import(
        source: InputStream,
        mode: ImportMode,
        withSettings: Boolean = true,
    ): ImportReport = when (val inspection = BackupReader.inspect(source)) {
        is BackupInspection.Unreadable -> ImportReport.failed(mode, inspection.error)
        is BackupInspection.Readable -> apply(inspection.preview, mode, withSettings)
    }

    suspend fun import(
        raw: String,
        mode: ImportMode,
        withSettings: Boolean = true,
    ): ImportReport = when (val inspection = BackupReader.inspect(raw)) {
        is BackupInspection.Unreadable -> ImportReport.failed(mode, inspection.error)
        is BackupInspection.Readable -> apply(inspection.preview, mode, withSettings)
    }

    /**
     * Write an already-validated preview.
     *
     * Note for the caller: once this returns, the automation state is stale by definition —
     * the windows it was scheduled against may no longer exist. Recomputing the next
     * transition and rearming the alarm is mandatory, not optional. An import that leaves
     * yesterday's alarm armed breaks the app's central promise while reporting success.
     */
    suspend fun apply(
        preview: BackupPreview,
        mode: ImportMode,
        withSettings: Boolean = true,
    ): ImportReport {
        val profileDao = database.profileDao()
        val scheduleDao = database.scheduleDao()

        val corrections = preview.corrections.toMutableList()
        val rejections = preview.rejections.toMutableList()

        // ADD_AS_COPY rewrites identity here, outside the transaction: the mapping is
        // decided once, and the transaction stays as short as it can be.
        val incoming: List<Pair<SoundProfile, List<Schedule>>> = when (mode) {
            ImportMode.ADD_AS_COPY -> preview.profiles.map { profile ->
                val newUuid = uuidGenerator.newUuid()
                corrections += ImportCorrection.UuidRegenerated(profile.uuid, newUuid)
                val schedules = preview.schedulesByProfileUuid[profile.uuid].orEmpty().map {
                    it.copy(
                        id = ScheduleId.UNSAVED,
                        uuid = uuidGenerator.newUuid(),
                        profileUuid = newUuid,
                    )
                }
                profile.copy(id = ProfileId.UNSAVED, uuid = newUuid) to schedules
            }

            ImportMode.REPLACE_ALL, ImportMode.MERGE -> preview.profiles.map { profile ->
                profile to preview.schedulesByProfileUuid[profile.uuid].orEmpty()
            }
        }

        var inserted = 0
        var updated = 0
        var skipped = 0
        var schedulesInserted = 0

        // One transaction for the whole thing. A half-applied import is the worst possible
        // outcome: a configuration that is neither the one the user had nor the one they
        // asked for, with no way to tell which parts are which.
        database.withTransaction {
            if (mode == ImportMode.REPLACE_ALL) {
                // Windows go with their profiles by cascade. The activity log survives:
                // its foreign keys are SET NULL and it keeps the denormalised profile
                // name, so the record of what happened before the import stays readable.
                profileDao.deleteAll()
            }

            for ((profile, schedules) in incoming) {
                val existing = profileDao.getByUuid(profile.uuid)

                if (mode == ImportMode.MERGE && existing != null &&
                    existing.updatedAt.isAfter(profile.updatedAt)
                ) {
                    // Restoring an old backup onto a phone that has moved on must not
                    // quietly undo weeks of edits.
                    rejections += ImportRejection.OlderThanExisting(profile.uuid)
                    skipped++
                    continue
                }

                profileDao.upsertByUuid(profile.toEntity(id = existing?.id ?: 0L))
                if (existing == null) inserted++ else updated++

                // Replace rather than merge the windows: they are meaningful only as a
                // set, and half of an old set plus half of a new one is a schedule the
                // user never wrote.
                scheduleDao.replaceForProfile(
                    profileUuid = profile.uuid,
                    schedules = schedules.map { it.toEntity(id = 0L) },
                )
                schedulesInserted += schedules.size
            }
        }

        var settingsApplied = false
        val importedSettings = preview.settings
        if (withSettings && importedSettings != null) {
            // Deliberately outside the transaction: DataStore and Room do not share one,
            // and pretending otherwise would only hide the fact. Preferences are also the
            // part whose loss is harmless — at worst a theme stays as it was.
            writeSettings(importedSettings, incoming.map { it.first.uuid }.toSet())
            settingsApplied = true
        }

        return ImportReport(
            mode = mode,
            applied = true,
            profilesInserted = inserted,
            profilesUpdated = updated,
            profilesSkipped = skipped,
            schedulesInserted = schedulesInserted,
            settingsApplied = settingsApplied,
            corrections = corrections,
            rejections = rejections,
        )
    }

    private suspend fun writeSettings(settings: SettingsDto, importedUuids: Set<String>) {
        userPreferences.setThemeMode(
            ThemeMode.entries.firstOrNull { it.name == settings.themeMode } ?: ThemeMode.SYSTEM,
        )
        userPreferences.setDynamicColor(settings.dynamicColor)
        userPreferences.setLanguageTag(settings.languageTag)
        userPreferences.setMaxReliabilityMode(settings.maxReliabilityMode)
        // A default profile that was not imported is a dangling reference, and a dangling
        // default means "fall back to something that does not exist" — that is, to nothing,
        // with no explanation. Dropping it falls back to restoring the baseline, which is
        // the documented behaviour when there is no default at all.
        userPreferences.setDefaultProfileUuid(
            settings.defaultProfileUuid?.takeIf { it in importedUuids },
        )
    }
}
