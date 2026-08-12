package com.sonoritmo.core.domain.model

import java.time.Instant

/** Built-in template a profile was created from, if any (RF-07). */
enum class ProfileTemplate { NIGHT, WORK, MEETING, CINEMA, DRIVING, WEEKEND }

/**
 * A complete desired audio state. Not "a volume" — the whole thing: every writable
 * stream, the ringer mode and Do Not Disturb.
 */
data class SoundProfile(
    val id: ProfileId = ProfileId.UNSAVED,
    /** Stable business identity. Travels in the export JSON; the row id never does. */
    val uuid: String,
    val name: String,
    val emoji: String? = null,
    /** ARGB, `0xFFRRGGBB`. Seeds the accent colour when dynamic colour is off. */
    val colorSeed: Int = DEFAULT_COLOR_SEED,
    val enabled: Boolean = true,
    /** 0..100. Higher wins when two schedules overlap. */
    val priority: Int = DEFAULT_PRIORITY,
    val sortOrder: Int = 0,
    val templateKey: ProfileTemplate? = null,
    val volumes: VolumeSettings = VolumeSettings.UNCHANGED,
    val ringerMode: RingerMode? = null,
    val dnd: DndSettings = DndSettings.UNCHANGED,
    val options: ProfileOptions = ProfileOptions.DEFAULT,
    /** Id returned by `NotificationManager.addAutomaticZenRule`, so we can update/remove it. */
    val zenRuleId: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    /**
     * A profile that changes nothing is almost always a user mistake, and it makes the
     * activity log unreadable. The editor blocks saving one.
     */
    val changesNothing: Boolean
        get() = volumes.isEmpty && ringerMode == null && dnd.mode == null

    /**
     * Normalises the two ways the model can express contradictory orders.
     *
     * On Android, `ringerMode` and `volumes.ring` are not independent: writing ring to 0
     * flips the ringer mode by itself on most devices, and `setRingerMode(NORMAL)`
     * rewrites the ring volume from a value the system kept. So `{NORMAL, ring = 0}` and
     * `{SILENT, ring = 80}` are contradictions whose outcome depends on apply order.
     *
     * Rules (docs/02, amendment E-05):
     *  - `ringerMode` silences the ring  ⇒ `volumes.ring` is dropped to `null`.
     *  - `ringerMode == NORMAL`          ⇒ `volumes.ring == 0` is raised to 1.
     */
    fun normalized(): SoundProfile {
        val mode = ringerMode
        val newVolumes = when {
            mode != null && mode.silencesRing -> volumes.copy(ring = null)
            mode == RingerMode.NORMAL && volumes.ring == 0 -> volumes.copy(ring = 1)
            else -> volumes
        }
        return if (newVolumes == volumes) this else copy(volumes = newVolumes)
    }

    fun validate(): List<ValidationIssue> = buildList {
        if (name.isBlank()) add(ValidationIssue.NAME_BLANK)
        if (name.length > MAX_NAME_LENGTH) add(ValidationIssue.NAME_TOO_LONG)
        if (uuid.isBlank()) add(ValidationIssue.UUID_BLANK)
        if (priority !in MIN_PRIORITY..MAX_PRIORITY) add(ValidationIssue.PRIORITY_OUT_OF_RANGE)
        if ((emoji?.codePointCount(0, emoji.length) ?: 0) > MAX_EMOJI_CODE_POINTS) {
            add(ValidationIssue.EMOJI_TOO_LONG)
        }
        if (options.transitionSeconds !in 0..ProfileOptions.MAX_TRANSITION_SECONDS) {
            add(ValidationIssue.TRANSITION_OUT_OF_RANGE)
        }
        AudioStream.WRITABLE.forEach { stream ->
            val percent = volumes[stream]
            if (percent != null && percent !in VolumeSettings.MIN_PERCENT..VolumeSettings.MAX_PERCENT) {
                add(ValidationIssue.VOLUME_OUT_OF_RANGE)
            }
        }
        if (ringerMode == RingerMode.NORMAL && volumes.ring == 0) {
            add(ValidationIssue.RINGER_CONTRADICTS_VOLUME)
        }
        if (changesNothing) add(ValidationIssue.PROFILE_CHANGES_NOTHING)
    }

    companion object {
        const val MIN_PRIORITY = 0
        const val MAX_PRIORITY = 100
        const val DEFAULT_PRIORITY = 50
        const val MAX_NAME_LENGTH = 60
        const val MAX_EMOJI_CODE_POINTS = 8
        const val DEFAULT_COLOR_SEED = 0xFF4A6FA5.toInt()
    }
}

enum class ValidationIssue {
    NAME_BLANK,
    NAME_TOO_LONG,
    UUID_BLANK,
    PRIORITY_OUT_OF_RANGE,
    EMOJI_TOO_LONG,
    TRANSITION_OUT_OF_RANGE,
    VOLUME_OUT_OF_RANGE,
    RINGER_CONTRADICTS_VOLUME,
    PROFILE_CHANGES_NOTHING,
    SCHEDULE_NO_DAYS,
    SCHEDULE_START_OUT_OF_RANGE,
    SCHEDULE_DURATION_OUT_OF_RANGE,
    SCHEDULE_ORPHANED,
}
