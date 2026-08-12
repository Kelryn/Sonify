package com.sonoritmo.core.data.database

import androidx.room.TypeConverter
import com.sonoritmo.core.data.mapper.EnumCodecs
import com.sonoritmo.core.domain.model.CallPolicy
import com.sonoritmo.core.domain.model.ConversationPolicy
import com.sonoritmo.core.domain.model.LogReason
import com.sonoritmo.core.domain.model.LogType
import com.sonoritmo.core.domain.model.MessagePolicy
import com.sonoritmo.core.domain.model.RingerMode
import java.time.Instant

/**
 * Room type converters.
 *
 * All of them delegate to [EnumCodecs] so that the database and the backup file agree,
 * by construction, on how every value is spelled.
 *
 * ## Why some enum columns are still plain `String` in the entities
 *
 * Room allows exactly **one** converter per type pair, so a converter has exactly one
 * answer for a code it does not recognise. That answer has to be different depending on
 * the column:
 *
 *  - Columns where "unknown" must read back as `null` — `ringer_mode`, `dnd_mode`,
 *    `template_key` — because `null` is this model's one and only way of saying
 *    "do not touch" (docs/02, amendment E-03). Reading a corrupt `ringer_mode` as
 *    `NORMAL` would make the phone ring at 3 a.m., which is the exact bug the app
 *    exists to prevent. Those columns stay `String?` in the entity and are decoded in
 *    the mappers, where the fallback can be `null`.
 *  - Columns that are non-nullable and have a well-defined conservative default —
 *    log `type`/`reason`, the three DND sender policies, the snapshot ringer mode.
 *    Those use the converters below.
 *
 * Declaring the converters with non-null parameters is deliberate: Room has been
 * null-aware since 2.3 and generates the null check around a non-null converter, so a
 * nullable column keeps working without the converter having to invent a value for
 * `null`.
 */
object Converters {

    // ── Instant ──────────────────────────────────────────────────────────────
    //
    // Epoch milliseconds UTC, in an INTEGER column: comparable and sortable in SQL,
    // which a formatted string would not be. The local wall-clock context a user
    // actually saw is preserved separately in `activity_log.zone_id`, because an
    // instant alone cannot reconstruct it after a trip or a clock change.

    @TypeConverter
    fun instantToEpochMillis(value: Instant): Long = value.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    // ── Enums with a safe non-null default ───────────────────────────────────

    @TypeConverter
    fun logTypeToCode(value: LogType): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToLogType(value: String): LogType = EnumCodecs.logType(value)

    @TypeConverter
    fun logReasonToCode(value: LogReason): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToLogReason(value: String): LogReason = EnumCodecs.logReason(value)

    @TypeConverter
    fun callPolicyToCode(value: CallPolicy): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToCallPolicy(value: String): CallPolicy = EnumCodecs.callPolicy(value)

    @TypeConverter
    fun messagePolicyToCode(value: MessagePolicy): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToMessagePolicy(value: String): MessagePolicy = EnumCodecs.messagePolicy(value)

    @TypeConverter
    fun conversationPolicyToCode(value: ConversationPolicy): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToConversationPolicy(value: String): ConversationPolicy =
        EnumCodecs.conversationPolicy(value)

    /** Only used by `audio_snapshots.ringer_mode`, which is captured, never absent. */
    @TypeConverter
    fun ringerModeToCode(value: RingerMode): String = EnumCodecs.code(value)

    @TypeConverter
    fun codeToRingerMode(value: String): RingerMode = EnumCodecs.ringerModeOrNormal(value)
}
