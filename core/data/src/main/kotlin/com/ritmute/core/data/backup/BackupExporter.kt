package com.ritmute.core.data.backup

import com.ritmute.core.data.preferences.UserPreferences
import com.ritmute.core.data.repository.ProfileRepository
import com.ritmute.core.data.repository.ScheduleRepository
import com.ritmute.core.domain.port.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads the user's configuration and hands it to [BackupWriter].
 *
 * This class holds nothing but the wiring; every decision about the *format* lives in
 * [BackupWriter] and [BackupFormat], where it can be tested without a device.
 */
@Singleton
class BackupExporter @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val scheduleRepository: ScheduleRepository,
    private val userPreferences: UserPreferences,
    private val timeSource: TimeSource,
) {

    /**
     * Export the configuration.
     *
     * The activity log is deliberately not part of this. It is not configuration, it
     * multiplies the size of the file, and it is a minute-by-minute record of when
     * somebody sleeps and when they are in meetings — nobody sending a friend their
     * profile setup expects to be sending that too. The history has its own export.
     *
     * @param includeSettings false when the file is meant to be shared rather than
     *   restored, so the recipient's theme and language are left alone.
     */
    suspend fun export(
        identity: ExporterIdentity = ExporterIdentity(),
        includeSettings: Boolean = true,
    ): ExportSummary = BackupWriter.serialize(
        profiles = profileRepository.getAll(),
        schedules = scheduleRepository.getAll(),
        settings = if (includeSettings) userPreferences.get() else null,
        exportedAt = timeSource.now(),
        identity = identity,
    )
}
