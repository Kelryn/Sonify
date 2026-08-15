package com.ritmute.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.ritmute.core.domain.model.CallPolicy
import com.ritmute.core.domain.model.ConversationPolicy
import com.ritmute.core.domain.model.MessagePolicy
import java.time.Instant

/**
 * A sound profile as stored.
 *
 * ## Two identities, on purpose
 *
 * [id] is the local rowid: it is `AUTOINCREMENT`, so SQLite never reuses the number of a
 * deleted row, and it never leaves the app. [uuid] is the business identity — it travels
 * in the backup file, it is what schedules, the activity log and the automation state
 * point at, and it is the last tie-breaker of the conflict resolver, which is what makes
 * two phones holding the same configuration behave identically (docs/02, decision D-C6).
 *
 * ## Why `AUTOINCREMENT` matters here specifically
 *
 * Without it, deleting profile 7 and creating a new one would hand the newcomer id 7.
 * Anything that persisted the number outside the database — a `PendingIntent` request
 * code, a notification id, a configured widget — would then point at the wrong profile.
 * (Those should reference the uuid anyway, which is the other half of the rule.)
 */
@Entity(
    tableName = "profiles",
    indices = [
        Index(value = ["uuid"], unique = true),
        // Serves the hot reconciliation read: enabled profiles, best priority first.
        Index(value = ["enabled", "priority"]),
        Index(value = ["sort_order"]),
    ],
)
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "name")
    val name: String,

    /** At most 8 code points, enforced on save and on import. */
    @ColumnInfo(name = "emoji")
    val emoji: String? = null,

    /** ARGB, `0xFFRRGGBB`, stored as the signed Int that Android's colour APIs use. */
    @ColumnInfo(name = "color_seed")
    val colorSeed: Int,

    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /** 0..100. Range is enforced in the domain: Room cannot express a `CHECK`. */
    @ColumnInfo(name = "priority", defaultValue = "50")
    val priority: Int = 50,

    @ColumnInfo(name = "sort_order", defaultValue = "0")
    val sortOrder: Int = 0,

    /**
     * `ProfileTemplate` code, or null for a profile built from scratch.
     * Kept as raw text so an unreadable code degrades to "no template" rather than
     * to a wrong one — see [com.ritmute.core.data.database.Converters].
     */
    @ColumnInfo(name = "template_key")
    val templateKey: String? = null,

    @Embedded(prefix = "vol_")
    val volumes: VolumeColumns,

    /** `RingerMode` code; null means "do not touch". Raw text, see [templateKey]. */
    @ColumnInfo(name = "ringer_mode")
    val ringerMode: String? = null,

    @Embedded(prefix = "dnd_")
    val dnd: DndColumns,

    @Embedded(prefix = "opt_")
    val options: OptionColumns,

    /**
     * Identifier handed back by `NotificationManager.addAutomaticZenRule`.
     *
     * Local system state: it is **never exported**. Two phones believing they own the
     * same system rule is a defect with consequences outside the app — and losing it is
     * worse, because the orphaned Mode then sits in the system settings forever with no
     * way to remove it from here (docs/02, amendment E-11).
     */
    @ColumnInfo(name = "zen_rule_id")
    val zenRuleId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Instant,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Instant,
)

/**
 * Desired volume per stream, as a percentage 0..100. `null` = do not touch.
 *
 * There is no accessibility column: `setStreamVolume(STREAM_ACCESSIBILITY, …)` is a
 * silent no-op without a signature-level permission, so the stream is captured in
 * snapshots but never configured by a profile (docs/02, decision D-C2).
 */
data class VolumeColumns(
    @ColumnInfo(name = "ring") val ring: Int? = null,
    @ColumnInfo(name = "notification") val notification: Int? = null,
    @ColumnInfo(name = "music") val music: Int? = null,
    @ColumnInfo(name = "alarm") val alarm: Int? = null,
    @ColumnInfo(name = "system") val system: Int? = null,
    @ColumnInfo(name = "voice_call") val voiceCall: Int? = null,
)

/**
 * What the profile wants from Do Not Disturb.
 *
 * `mode == null` means "do not touch DND at all", which is also the signal used to skip
 * creating a system zen rule: rules are capped at 100 per package and most profiles only
 * move volumes (docs/02, amendment E-17).
 */
data class DndColumns(
    @ColumnInfo(name = "mode") val mode: String? = null,
    @ColumnInfo(name = "allow_calls", defaultValue = "'NONE'")
    val allowCalls: CallPolicy = CallPolicy.NONE,
    @ColumnInfo(name = "allow_repeat_callers", defaultValue = "1")
    val allowRepeatCallers: Boolean = true,
    @ColumnInfo(name = "allow_messages", defaultValue = "'NONE'")
    val allowMessages: MessagePolicy = MessagePolicy.NONE,
    @ColumnInfo(name = "allow_conversations", defaultValue = "'NONE'")
    val allowConversations: ConversationPolicy = ConversationPolicy.NONE,
    @ColumnInfo(name = "allow_alarms", defaultValue = "1")
    val allowAlarms: Boolean = true,
    @ColumnInfo(name = "allow_media", defaultValue = "1")
    val allowMedia: Boolean = true,
    @ColumnInfo(name = "allow_reminders", defaultValue = "0")
    val allowReminders: Boolean = false,
    @ColumnInfo(name = "allow_events", defaultValue = "0")
    val allowEvents: Boolean = false,
    /** Bitmask of `NotificationManager.Policy.SUPPRESSED_EFFECT_*`. 0 = suppress nothing. */
    @ColumnInfo(name = "suppressed_visual_effects", defaultValue = "0")
    val suppressedVisualEffects: Int = 0,
)

/** Per-profile behaviour switches. `transition_seconds == 0` *is* "no ramp". */
data class OptionColumns(
    @ColumnInfo(name = "restore_on_exit", defaultValue = "1")
    val restoreOnExit: Boolean = true,
    @ColumnInfo(name = "transition_seconds", defaultValue = "0")
    val transitionSeconds: Int = 0,
    @ColumnInfo(name = "skip_during_call", defaultValue = "1")
    val skipDuringCall: Boolean = true,
    @ColumnInfo(name = "skip_if_media_playing", defaultValue = "0")
    val skipIfMediaPlaying: Boolean = false,
    @ColumnInfo(name = "notify_on_apply", defaultValue = "0")
    val notifyOnApply: Boolean = false,
)

/**
 * A profile together with its windows, read in one shot.
 *
 * The relation is keyed on `uuid`, not on the rowid, for the same reason the foreign key
 * is: everything above this layer speaks uuids, so joining on the rowid would only add a
 * translation step that can go wrong.
 */
data class ProfileWithSchedules(
    @Embedded val profile: ProfileEntity,
    @Relation(parentColumn = "uuid", entityColumn = "profile_uuid")
    val schedules: List<ScheduleEntity>,
)
