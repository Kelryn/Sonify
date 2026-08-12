package com.sonoritmo.core.data.mapper

import com.sonoritmo.core.data.database.entity.AudioSnapshotEntity
import com.sonoritmo.core.domain.model.AudioSnapshot
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.StreamLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Wire shape of one stream inside `audio_snapshots.levels_json`. */
@Serializable
internal data class StreamLevelDto(
    val steps: Int,
    val minSteps: Int,
    val maxSteps: Int,
)

/**
 * The serializer for the levels column.
 *
 * `ignoreUnknownKeys` is the whole reason this column is a document: the day Android
 * adds a stream, an older build reads the snapshot, ignores the stream it does not know
 * and still restores the six it does — instead of crashing or needing a migration.
 */
private val levelsCodec = Json {
    ignoreUnknownKeys = true
    isLenient = false
    encodeDefaults = true
}

internal fun Map<AudioStream, StreamLevel>.encodeLevels(): String {
    // Sorted by the enum's declaration order so the same device state always produces
    // the same string: a snapshot that differs only in map iteration order would make
    // any equality check on the column meaningless.
    val wire = AudioStream.entries.mapNotNull { stream ->
        this[stream]?.let { level ->
            stream.name to StreamLevelDto(
                steps = level.steps,
                minSteps = level.minSteps,
                maxSteps = level.maxSteps,
            )
        }
    }.toMap()
    return levelsCodec.encodeToString(wire)
}

internal fun decodeLevels(raw: String): Map<AudioStream, StreamLevel> {
    val wire: Map<String, StreamLevelDto> = levelsCodec.decodeFromString(raw)
    return AudioStream.entries.mapNotNull { stream ->
        wire[stream.name]?.let { dto ->
            stream to StreamLevel(
                steps = dto.steps,
                minSteps = dto.minSteps,
                maxSteps = dto.maxSteps,
            )
        }
    }.toMap()
}

fun AudioSnapshotEntity.toDomain(): AudioSnapshot = AudioSnapshot(
    capturedAt = capturedAt,
    levels = decodeLevels(levelsJson),
    ringerMode = ringerMode,
    interruptionFilter = interruptionFilter,
    ownerProfileUuid = ownerProfileUuid,
)

/**
 * @param deviceFingerprint `Build.MODEL` plus the volume scales, supplied by the system
 *   layer. It is not part of the domain type because it describes the *hardware the
 *   snapshot belongs to*, not the audio state — and the domain must stay free of
 *   anything device-specific.
 */
fun AudioSnapshot.toEntity(deviceFingerprint: String): AudioSnapshotEntity = AudioSnapshotEntity(
    id = AudioSnapshotEntity.BASELINE_ID,
    capturedAt = capturedAt,
    ownerProfileUuid = ownerProfileUuid,
    levelsJson = levels.encodeLevels(),
    ringerMode = ringerMode,
    interruptionFilter = interruptionFilter,
    deviceFingerprint = deviceFingerprint,
)
