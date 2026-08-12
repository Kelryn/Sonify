package com.sonoritmo.core.data.mapper

import com.sonoritmo.core.data.database.entity.DndColumns
import com.sonoritmo.core.data.database.entity.OptionColumns
import com.sonoritmo.core.data.database.entity.ProfileEntity
import com.sonoritmo.core.data.database.entity.VolumeColumns
import com.sonoritmo.core.domain.model.DndSettings
import com.sonoritmo.core.domain.model.ProfileId
import com.sonoritmo.core.domain.model.ProfileOptions
import com.sonoritmo.core.domain.model.SoundProfile
import com.sonoritmo.core.domain.model.VolumeSettings

/**
 * Entity ↔ domain, both directions, by hand.
 *
 * Written out rather than generated or reflected because this is the seam where a silent
 * mistake is most expensive: a field quietly dropped here is a setting the user saved and
 * the app forgot, with no error anywhere. Explicit code makes the omission a compile error
 * the day a field is added to either side.
 */
fun ProfileEntity.toDomain(): SoundProfile = SoundProfile(
    id = ProfileId(id),
    uuid = uuid,
    name = name,
    emoji = emoji,
    colorSeed = colorSeed,
    enabled = enabled,
    priority = priority,
    sortOrder = sortOrder,
    templateKey = EnumCodecs.profileTemplate(templateKey),
    volumes = volumes.toDomain(),
    ringerMode = EnumCodecs.ringerMode(ringerMode),
    dnd = dnd.toDomain(),
    options = options.toDomain(),
    zenRuleId = zenRuleId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/**
 * @param id the rowid to write. Callers that are updating pass the existing one; the DAO
 *   resolves it from the uuid anyway, so the domain object never has to carry a valid
 *   rowid for a write to be correct.
 */
fun SoundProfile.toEntity(id: Long = this.id.value): ProfileEntity = ProfileEntity(
    id = id,
    uuid = uuid,
    name = name,
    emoji = emoji,
    colorSeed = colorSeed,
    enabled = enabled,
    priority = priority,
    sortOrder = sortOrder,
    templateKey = templateKey?.let { EnumCodecs.code(it) },
    volumes = volumes.toColumns(),
    ringerMode = ringerMode?.let { EnumCodecs.code(it) },
    dnd = dnd.toColumns(),
    options = options.toColumns(),
    zenRuleId = zenRuleId,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

// ── Volumes ──────────────────────────────────────────────────────────────────

fun VolumeColumns.toDomain(): VolumeSettings = VolumeSettings(
    ring = ring,
    notification = notification,
    music = music,
    alarm = alarm,
    system = system,
    voiceCall = voiceCall,
)

fun VolumeSettings.toColumns(): VolumeColumns = VolumeColumns(
    ring = ring,
    notification = notification,
    music = music,
    alarm = alarm,
    system = system,
    voiceCall = voiceCall,
)

// ── Do Not Disturb ───────────────────────────────────────────────────────────

fun DndColumns.toDomain(): DndSettings = DndSettings(
    mode = EnumCodecs.dndMode(mode),
    allowCalls = allowCalls,
    allowRepeatCallers = allowRepeatCallers,
    allowMessages = allowMessages,
    allowConversations = allowConversations,
    allowAlarms = allowAlarms,
    allowMedia = allowMedia,
    allowReminders = allowReminders,
    allowEvents = allowEvents,
    suppressedVisualEffects = suppressedVisualEffects,
)

fun DndSettings.toColumns(): DndColumns = DndColumns(
    mode = mode?.let { EnumCodecs.code(it) },
    allowCalls = allowCalls,
    allowRepeatCallers = allowRepeatCallers,
    allowMessages = allowMessages,
    allowConversations = allowConversations,
    allowAlarms = allowAlarms,
    allowMedia = allowMedia,
    allowReminders = allowReminders,
    allowEvents = allowEvents,
    suppressedVisualEffects = suppressedVisualEffects,
)

// ── Options ──────────────────────────────────────────────────────────────────

fun OptionColumns.toDomain(): ProfileOptions = ProfileOptions(
    restoreOnExit = restoreOnExit,
    transitionSeconds = transitionSeconds,
    skipDuringCall = skipDuringCall,
    skipIfMediaPlaying = skipIfMediaPlaying,
    notifyOnApply = notifyOnApply,
)

fun ProfileOptions.toColumns(): OptionColumns = OptionColumns(
    restoreOnExit = restoreOnExit,
    transitionSeconds = transitionSeconds,
    skipDuringCall = skipDuringCall,
    skipIfMediaPlaying = skipIfMediaPlaying,
    notifyOnApply = notifyOnApply,
)
