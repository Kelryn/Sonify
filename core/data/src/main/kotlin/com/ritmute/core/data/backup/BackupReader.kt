package com.ritmute.core.data.backup

import com.ritmute.core.data.mapper.EnumCodecs
import com.ritmute.core.domain.model.DndSettings
import com.ritmute.core.domain.model.ProfileId
import com.ritmute.core.domain.model.ProfileOptions
import com.ritmute.core.domain.model.Schedule
import com.ritmute.core.domain.model.ScheduleId
import com.ritmute.core.domain.model.SoundProfile
import com.ritmute.core.domain.model.VolumeSettings
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Parses and validates a backup file. Touches nothing.
 *
 * ## Every field is treated as hostile
 *
 * A file arrives through the document picker, from an arbitrary app, possibly hand-edited
 * with a text editor. So:
 *
 *  - the byte ceiling is applied **before** parsing. The attack is the parse itself: the
 *    stream's real length is whatever the sending app decides, and `readBytes()` on it is
 *    an out-of-memory crash waiting to happen;
 *  - lengths are truncated, numbers clamped, unrecognised enum codes replaced by the
 *    model's own defaults — every one of them reported as a correction, never applied in
 *    silence;
 *  - a window with no days, or a profile that changes nothing, is dropped. Both are
 *    inapplicable by construction, so storing them would only leave a row the user keeps
 *    looking at while nothing happens;
 *  - the same uuid twice in one file is fatal for the whole file, because there is no way
 *    to know which of the two the user meant to keep.
 *
 * Nothing here throws on bad input. A malformed file is a value, not an exception: the
 * caller has a screen to draw either way.
 */
object BackupReader {

    /** Reads at most [BackupFormat.MAX_FILE_BYTES] from [source]. */
    fun inspect(source: InputStream): BackupInspection = when (val read = readLimited(source)) {
        is ReadResult.Text -> inspect(read.value)
        is ReadResult.Exceeded -> BackupInspection.Unreadable(
            ImportError.TooLarge(read.atLeastBytes, BackupFormat.MAX_FILE_BYTES),
        )
        ReadResult.Failed -> BackupInspection.Unreadable(
            ImportError.NotAValidBackup("the file could not be read"),
        )
    }

    fun inspect(raw: String): BackupInspection {
        val size = raw.toByteArray().size.toLong()
        if (size > BackupFormat.MAX_FILE_BYTES) {
            return BackupInspection.Unreadable(
                ImportError.TooLarge(size, BackupFormat.MAX_FILE_BYTES),
            )
        }

        val root = try {
            BackupFormat.json.parseToJsonElement(raw) as? JsonObject
                ?: return BackupInspection.Unreadable(
                    ImportError.NotAValidBackup("root is not a JSON object"),
                )
        } catch (cause: SerializationException) {
            return BackupInspection.Unreadable(
                ImportError.NotAValidBackup(cause.message ?: "malformed JSON"),
            )
        }

        val version = root["schemaVersion"]?.jsonPrimitive?.intOrNull
            ?: return BackupInspection.Unreadable(ImportError.MissingSchemaVersion)

        // A file from a newer build is refused outright. Reading a format we do not know
        // "to see if it works" keeps working right up until the day it does not, and by
        // then it has overwritten somebody's configuration.
        if (version !in BackupFormat.MINIMUM_SUPPORTED_VERSION..BackupFormat.CURRENT_VERSION) {
            return BackupInspection.Unreadable(
                ImportError.UnsupportedVersion(
                    found = version,
                    supported =
                    BackupFormat.MINIMUM_SUPPORTED_VERSION..BackupFormat.CURRENT_VERSION,
                ),
            )
        }

        val upgraded = BackupUpgrades.upgrade(root, version)
            ?: return BackupInspection.Unreadable(
                ImportError.NotAValidBackup("no upgrade path from schemaVersion $version"),
            )

        val file = try {
            BackupFormat.json.decodeFromJsonElement(BackupFile.serializer(), upgraded)
        } catch (cause: SerializationException) {
            return BackupInspection.Unreadable(
                ImportError.NotAValidBackup(cause.message ?: "unexpected structure"),
            )
        }

        // An empty file is refused rather than imported: with REPLACE_ALL it would delete
        // everything the user has and put nothing back.
        if (file.profiles.isEmpty()) {
            return BackupInspection.Unreadable(ImportError.Empty)
        }

        duplicateUuidIn(file)?.let { duplicate ->
            return BackupInspection.Unreadable(ImportError.DuplicateUuid(duplicate))
        }

        val collector = Collector()
        val profiles = mutableListOf<SoundProfile>()
        val schedulesByProfile = mutableMapOf<String, List<Schedule>>()

        for (dto in file.profiles) {
            val profile = dto.toDomain(collector) ?: continue
            profiles += profile
            schedulesByProfile[profile.uuid] =
                dto.schedules.mapNotNull { it.toDomain(profile.uuid, collector) }
        }

        val declared = file.integrity

        return BackupInspection.Readable(
            BackupPreview(
                schemaVersion = file.schemaVersion,
                exportedAt = file.exportedAt,
                generator = file.generator,
                settings = file.settings,
                profiles = profiles,
                schedulesByProfileUuid = schedulesByProfile,
                corrections = collector.corrections.toList(),
                rejections = collector.rejections.toList(),
                checksumValid = declared?.let { it.sha256 == BackupFormat.checksum(upgraded) },
                // Compared against what the *file* declared, not against what survived
                // validation: a count that does not add up means truncation, which is a
                // different problem from a profile this build chose to reject.
                countsMismatch = declared != null && (
                    declared.profileCount != file.profiles.size ||
                        declared.scheduleCount != file.profiles.sumOf { it.schedules.size }
                    ),
            ),
        )
    }

    /**
     * Streamed, with a hard ceiling, stopping as soon as it is passed — so the
     * pathological case costs one buffer rather than the whole file.
     */
    private fun readLimited(source: InputStream): ReadResult {
        return try {
            val buffer = ByteArray(READ_CHUNK)
            val out = ByteArrayOutputStream()
            var total = 0L
            while (true) {
                val read = source.read(buffer)
                if (read == -1) break
                total += read
                if (total > BackupFormat.MAX_FILE_BYTES) return ReadResult.Exceeded(total)
                out.write(buffer, 0, read)
            }
            ReadResult.Text(out.toByteArray().toString(Charsets.UTF_8))
        } catch (_: IOException) {
            ReadResult.Failed
        }
    }

    /**
     * Profile and schedule uuids share one set on purpose: they are all v4 uuids, so a
     * collision between the two kinds is corruption just as surely as one within a kind.
     */
    private fun duplicateUuidIn(file: BackupFile): String? {
        val seen = mutableSetOf<String>()
        for (profile in file.profiles) {
            if (!seen.add(profile.uuid)) return profile.uuid
            for (schedule in profile.schedules) {
                if (!seen.add(schedule.uuid)) return schedule.uuid
            }
        }
        return null
    }

    private sealed interface ReadResult {
        data class Text(val value: String) : ReadResult

        /** @property atLeastBytes what had been read when the ceiling was passed. */
        data class Exceeded(val atLeastBytes: Long) : ReadResult

        data object Failed : ReadResult
    }

    private const val READ_CHUNK = 8 * 1024
}

// ── DTO to domain, collecting corrections and rejections ─────────────────────

/** Mutable accumulator, so a mapping function can report without returning two things. */
private class Collector {
    val corrections = mutableListOf<ImportCorrection>()
    val rejections = mutableListOf<ImportRejection>()
}

/** @return null when the profile could not be salvaged; the reason is in [collector]. */
private fun ProfileDto.toDomain(collector: Collector): SoundProfile? {
    val created = BackupFormat.parseInstant(createdAt) ?: run {
        // A profile with no readable creation date cannot take part in tie-breaking, and
        // inventing one would silently change which profile wins an overlap.
        collector.rejections += ImportRejection.UnreadableField(
            path = "profiles[$uuid].createdAt",
            raw = createdAt,
        )
        return null
    }
    val updated = BackupFormat.parseInstant(updatedAt) ?: created

    val safeName = clampText(
        value = name,
        limit = SoundProfile.MAX_NAME_LENGTH,
        path = "profiles[$uuid].name",
        collector = collector,
    )

    val safeEmoji = emoji?.let { raw ->
        clampCodePoints(
            value = raw,
            limit = SoundProfile.MAX_EMOJI_CODE_POINTS,
            path = "profiles[$uuid].emoji",
            collector = collector,
        )
    }

    val effects = SuppressedEffects.fromNames(dnd.suppressedVisualEffects)
    if (effects.unknownNames.isNotEmpty()) {
        collector.corrections += ImportCorrection.UnknownVisualEffects(uuid, effects.unknownNames)
    }

    val candidate = SoundProfile(
        id = ProfileId.UNSAVED,
        uuid = uuid,
        name = safeName,
        emoji = safeEmoji,
        colorSeed = BackupFormat.colorFromUnsigned(colorSeed),
        enabled = enabled,
        priority = clampInt(
            value = priority,
            range = SoundProfile.MIN_PRIORITY..SoundProfile.MAX_PRIORITY,
            path = "profiles[$uuid].priority",
            collector = collector,
        ),
        sortOrder = sortOrder,
        templateKey = decodeEnum(
            raw = templateKey,
            path = "profiles[$uuid].templateKey",
            collector = collector,
            fallbackLabel = "null",
            decode = { EnumCodecs.profileTemplate(it) },
        ),
        volumes = volumes.toDomain(uuid, collector),
        ringerMode = decodeEnum(
            raw = ringerMode,
            path = "profiles[$uuid].ringerMode",
            collector = collector,
            fallbackLabel = "null",
            decode = { EnumCodecs.ringerMode(it) },
        ),
        dnd = dnd.toDomain(uuid, effects.mask, collector),
        options = options.toDomain(uuid, collector),
        // Never imported: it names a rule in the exporting device's system settings.
        zenRuleId = null,
        createdAt = created,
        updatedAt = updated,
    )

    val normalized = candidate.normalized()
    if (normalized.volumes != candidate.volumes) {
        collector.corrections += ImportCorrection.RingerNormalized(uuid)
    }

    val issues = normalized.validate()
    if (issues.isNotEmpty()) {
        collector.rejections += ImportRejection.InvalidProfile(uuid, issues)
        return null
    }
    return normalized
}

private fun VolumesDto.toDomain(profileUuid: String, collector: Collector): VolumeSettings {
    fun clamp(value: Int?, stream: String): Int? = value?.let {
        clampInt(
            value = it,
            range = VolumeSettings.MIN_PERCENT..VolumeSettings.MAX_PERCENT,
            path = "profiles[$profileUuid].volumes.$stream",
            collector = collector,
        )
    }
    return VolumeSettings(
        ring = clamp(ring, "ring"),
        notification = clamp(notification, "notification"),
        music = clamp(music, "music"),
        alarm = clamp(alarm, "alarm"),
        system = clamp(system, "system"),
        voiceCall = clamp(voiceCall, "voiceCall"),
    )
}

private fun DndDto.toDomain(
    profileUuid: String,
    effectsMask: Int,
    collector: Collector,
): DndSettings = DndSettings(
    mode = decodeEnum(
        raw = mode,
        path = "profiles[$profileUuid].dnd.mode",
        collector = collector,
        fallbackLabel = "null",
        decode = { EnumCodecs.dndMode(it) },
    ),
    allowCalls = decodeRequiredEnum(
        raw = allowCalls,
        path = "profiles[$profileUuid].dnd.allowCalls",
        collector = collector,
        decode = { EnumCodecs.callPolicy(it) },
        encode = { EnumCodecs.code(it) },
    ),
    allowRepeatCallers = allowRepeatCallers,
    allowMessages = decodeRequiredEnum(
        raw = allowMessages,
        path = "profiles[$profileUuid].dnd.allowMessages",
        collector = collector,
        decode = { EnumCodecs.messagePolicy(it) },
        encode = { EnumCodecs.code(it) },
    ),
    allowConversations = decodeRequiredEnum(
        raw = allowConversations,
        path = "profiles[$profileUuid].dnd.allowConversations",
        collector = collector,
        decode = { EnumCodecs.conversationPolicy(it) },
        encode = { EnumCodecs.code(it) },
    ),
    allowAlarms = allowAlarms,
    allowMedia = allowMedia,
    allowReminders = allowReminders,
    allowEvents = allowEvents,
    suppressedVisualEffects = effectsMask,
)

private fun OptionsDto.toDomain(profileUuid: String, collector: Collector): ProfileOptions =
    ProfileOptions(
        restoreOnExit = restoreOnExit,
        transitionSeconds = clampInt(
            value = transitionSeconds,
            range = 0..ProfileOptions.MAX_TRANSITION_SECONDS,
            path = "profiles[$profileUuid].options.transitionSeconds",
            collector = collector,
        ),
        skipDuringCall = skipDuringCall,
        skipIfMediaPlaying = skipIfMediaPlaying,
        notifyOnApply = notifyOnApply,
    )

private fun ScheduleDto.toDomain(profileUuid: String, collector: Collector): Schedule? {
    val startMinute = BackupFormat.parseStartTime(startTime) ?: run {
        collector.rejections += ImportRejection.UnreadableField(
            path = "profiles[$profileUuid].schedules[$uuid].startTime",
            raw = startTime,
        )
        return null
    }

    val decoded = EnumCodecs.codesToDaysMask(daysOfWeek)
    decoded.unknownCodes.forEach { raw ->
        collector.corrections += ImportCorrection.UnknownEnumValue(
            path = "profiles[$profileUuid].schedules[$uuid].daysOfWeek",
            raw = raw,
            appliedAs = "ignored",
        )
    }

    val candidate = Schedule(
        id = ScheduleId.UNSAVED,
        uuid = uuid,
        profileUuid = profileUuid,
        enabled = enabled,
        startMinuteOfDay = startMinute,
        durationMinutes = clampInt(
            value = durationMinutes,
            range = 1..Schedule.MINUTES_PER_DAY,
            path = "profiles[$profileUuid].schedules[$uuid].durationMinutes",
            collector = collector,
        ),
        daysMask = decoded.mask,
        label = label?.let { raw ->
            clampText(
                value = raw,
                limit = MAX_LABEL_LENGTH,
                path = "profiles[$profileUuid].schedules[$uuid].label",
                collector = collector,
            )
        },
    )

    val issues = candidate.validate()
    if (issues.isNotEmpty()) {
        // A window with no days can never fire. Storing it would put a row in the database
        // that no code path will ever act on, and that the user would keep looking at,
        // wondering why nothing happens.
        collector.rejections += ImportRejection.InvalidSchedule(profileUuid, uuid, issues)
        return null
    }
    return candidate
}

// ── Field-level guards ───────────────────────────────────────────────────────

private const val MAX_LABEL_LENGTH = 60

private fun clampInt(value: Int, range: IntRange, path: String, collector: Collector): Int {
    if (value in range) return value
    val applied = value.coerceIn(range)
    collector.corrections +=
        ImportCorrection.ValueClamped(path, value.toString(), applied.toString())
    return applied
}

private fun clampText(value: String, limit: Int, path: String, collector: Collector): String {
    if (value.length <= limit) return value
    collector.corrections += ImportCorrection.TextTruncated(path, value.length, limit)
    return value.take(limit)
}

/**
 * Truncates by code point, not by char.
 *
 * `take(8)` on a string of emoji cuts a surrogate pair in half and leaves a replacement
 * character: a decorative field turning into a visible defect in every list row.
 */
private fun clampCodePoints(
    value: String,
    limit: Int,
    path: String,
    collector: Collector,
): String {
    val count = value.codePointCount(0, value.length)
    if (count <= limit) return value
    collector.corrections += ImportCorrection.TextTruncated(path, count, limit)
    return value.substring(0, value.offsetByCodePoints(0, limit))
}

/** Nullable enum: an unrecognised code becomes `null`, i.e. the model's "do not touch". */
private fun <E> decodeEnum(
    raw: String?,
    path: String,
    collector: Collector,
    fallbackLabel: String,
    decode: (String?) -> E?,
): E? {
    val decoded = decode(raw)
    if (raw != null && decoded == null) {
        collector.corrections += ImportCorrection.UnknownEnumValue(path, raw, fallbackLabel)
    }
    return decoded
}

/**
 * Non-nullable enum: an unrecognised code becomes the model's own default, which for every
 * one of these is the most restrictive option — letting fewer notifications through than
 * the user asked for is recoverable, letting more through at 3 a.m. is not.
 *
 * Recognition is decided by re-encoding rather than by comparing against `Enum.name`: the
 * stored code and the Kotlin identifier are allowed to diverge, and this check has to keep
 * working on the day they do.
 */
private fun <E> decodeRequiredEnum(
    raw: String,
    path: String,
    collector: Collector,
    decode: (String) -> E,
    encode: (E) -> String,
): E {
    val decoded = decode(raw)
    val roundTripped = encode(decoded)
    if (roundTripped != raw) {
        collector.corrections += ImportCorrection.UnknownEnumValue(path, raw, roundTripped)
    }
    return decoded
}
