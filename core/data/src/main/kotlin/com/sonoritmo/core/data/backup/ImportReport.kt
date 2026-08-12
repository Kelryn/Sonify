package com.sonoritmo.core.data.backup

import com.sonoritmo.core.domain.model.Schedule
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.ValidationIssue

/** How an import should treat what is already on the device. */
enum class ImportMode {
    /** Wipe the profiles and windows and take the file as the truth. */
    REPLACE_ALL,

    /**
     * Match on uuid: update an existing profile only when the incoming one is newer by
     * `updatedAt`, insert the rest, keep everything not mentioned in the file.
     *
     * "Newer wins" rather than "the file wins" so that restoring an old backup onto a
     * phone that has moved on does not quietly undo weeks of edits.
     */
    MERGE,

    /**
     * Regenerate every uuid, so nothing already on the device can be touched.
     *
     * The mode to reach for when importing a configuration someone else sent you.
     */
    ADD_AS_COPY,
}

/**
 * Why a file could not be read at all. Fatal: nothing is written when one of these is
 * returned.
 */
sealed interface ImportError {
    /** Not JSON, or not the shape of a backup. */
    data class NotAValidBackup(val detail: String) : ImportError

    /** `schemaVersion` missing entirely — this is not a SonoRitmo backup. */
    data object MissingSchemaVersion : ImportError

    /**
     * Written by a newer build. Refused deliberately rather than parsed hopefully:
     * guessing at an unknown format eventually guesses wrong.
     */
    data class UnsupportedVersion(val found: Int, val supported: IntRange) : ImportError

    data class TooLarge(val bytes: Long, val limit: Long) : ImportError

    /**
     * The same uuid twice in one file.
     *
     * Fatal for the whole file rather than a per-profile rejection: it means the file was
     * generated or edited wrongly, and there is no way to know which of the two the user
     * meant to keep.
     */
    data class DuplicateUuid(val uuid: String) : ImportError

    /** Valid JSON with no profile in it. Importing it would only destroy data. */
    data object Empty : ImportError
}

/** Something was accepted, but not exactly as written. */
sealed interface ImportCorrection {
    data class UnknownEnumValue(val path: String, val raw: String, val appliedAs: String) :
        ImportCorrection

    data class ValueClamped(val path: String, val raw: String, val applied: String) :
        ImportCorrection

    data class TextTruncated(val path: String, val originalLength: Int, val limit: Int) :
        ImportCorrection

    /**
     * `ringerMode` and `volumes.ring` disagreed and the model's normalisation settled it.
     * Worth surfacing because the user's file said one thing and the app stored another.
     */
    data class RingerNormalized(val profileUuid: String) : ImportCorrection

    /** Unknown suppressed-effect names, dropped because this build cannot express them. */
    data class UnknownVisualEffects(val profileUuid: String, val names: List<String>) :
        ImportCorrection

    /** [ImportMode.ADD_AS_COPY] rewrote the identity so nothing existing was touched. */
    data class UuidRegenerated(val originalUuid: String, val newUuid: String) : ImportCorrection
}

/** Something was dropped. The rest of the file is still imported. */
sealed interface ImportRejection {
    data class InvalidProfile(val uuid: String, val issues: List<ValidationIssue>) :
        ImportRejection

    data class InvalidSchedule(
        val profileUuid: String,
        val scheduleUuid: String,
        val issues: List<ValidationIssue>,
    ) : ImportRejection

    /** A field that could not be read at all, e.g. an unparseable timestamp or time. */
    data class UnreadableField(val path: String, val raw: String) : ImportRejection

    /** [ImportMode.MERGE] found a newer profile already on the device and kept it. */
    data class OlderThanExisting(val uuid: String) : ImportRejection
}

/** A parsed, validated file that has not touched the database yet. */
data class BackupPreview(
    val schemaVersion: Int,
    val exportedAt: String,
    val generator: GeneratorDto,
    val settings: SettingsDto?,
    val profiles: List<SoundProfile>,
    val schedulesByProfileUuid: Map<String, List<Schedule>>,
    val corrections: List<ImportCorrection>,
    val rejections: List<ImportRejection>,
    /**
     * Null when the file carried no checksum, false when it carried one that does not
     * match. False is reported and never blocks: hand-editing the JSON of a GPLv3 app is
     * a legitimate thing to do, and refusing to read the result would be hostile.
     */
    val checksumValid: Boolean?,
    /** True when the declared counts do not match what was actually read — truncation. */
    val countsMismatch: Boolean,
) {
    val scheduleCount: Int get() = schedulesByProfileUuid.values.sumOf { it.size }
}

/** The outcome of reading a file. Nothing has been written either way. */
sealed interface BackupInspection {
    data class Readable(val preview: BackupPreview) : BackupInspection
    data class Unreadable(val error: ImportError) : BackupInspection
}

/**
 * What an import actually did: what went in, what was adjusted, what was left out.
 *
 * Every field exists so the confirmation screen can say something specific. "Imported"
 * with no detail is how a user finds out three days later that two of their schedules
 * never arrived.
 */
data class ImportReport(
    val mode: ImportMode,
    val applied: Boolean,
    val profilesInserted: Int = 0,
    val profilesUpdated: Int = 0,
    val profilesSkipped: Int = 0,
    val schedulesInserted: Int = 0,
    val settingsApplied: Boolean = false,
    val corrections: List<ImportCorrection> = emptyList(),
    val rejections: List<ImportRejection> = emptyList(),
    /** Non-null only when nothing was applied. */
    val error: ImportError? = null,
) {
    val profilesTouched: Int get() = profilesInserted + profilesUpdated

    companion object {
        fun failed(mode: ImportMode, error: ImportError): ImportReport =
            ImportReport(mode = mode, applied = false, error = error)
    }
}
