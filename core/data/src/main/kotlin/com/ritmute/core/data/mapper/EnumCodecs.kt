package com.ritmute.core.data.mapper

import com.ritmute.core.domain.model.CallPolicy
import com.ritmute.core.domain.model.ConversationPolicy
import com.ritmute.core.domain.model.DayMask
import com.ritmute.core.domain.model.DndMode
import com.ritmute.core.domain.model.LogReason
import com.ritmute.core.domain.model.LogType
import com.ritmute.core.domain.model.MessagePolicy
import com.ritmute.core.domain.model.ProfileTemplate
import com.ritmute.core.domain.model.RingerMode
import java.time.DayOfWeek

/**
 * The single source of truth for how every enum is written to disk — both to the
 * database and to the backup file.
 *
 * ## Why this exists at all
 *
 * Room 2.4+ converts enums automatically using `Enum.name`, and kotlinx-serialization
 * does the same. Both couple the persisted representation to a *Kotlin identifier*:
 * renaming `ALARMS_ONLY` to `ONLY_ALARMS` would silently corrupt every existing row and
 * every existing backup, and no schema validation would notice, because the schema
 * itself does not change. Writing the mapping out by hand costs a few lines and makes
 * the rename a compile error in this file instead of a data loss in the field.
 *
 * `ordinal` is never used: it couples the data to *declaration order*, which is worse
 * still — inserting a constant in the middle silently reinterprets old rows.
 *
 * ## Reading unknown codes
 *
 * A code we do not recognise can only come from a newer version of the app (the user
 * downgraded) or from a hand-edited backup — a legitimate case in a GPLv3 app. Decoding
 * therefore **never throws**:
 *
 *  - Nullable fields fall back to `null`, which the whole model already reads as
 *    "do not touch" (docs/02, amendment E-03). That is the safest possible reading of
 *    an instruction we cannot understand.
 *  - Non-nullable fields fall back to the same value the domain uses as its own default,
 *    documented one by one below.
 *
 * [RitMuteCodeFreeze] in the test source set fails the build if any code changes.
 */
object EnumCodecs {

    // ── RingerMode ───────────────────────────────────────────────────────────

    fun code(value: RingerMode): String = when (value) {
        RingerMode.NORMAL -> "NORMAL"
        RingerMode.VIBRATE -> "VIBRATE"
        RingerMode.SILENT -> "SILENT"
    }

    fun ringerMode(code: String?): RingerMode? = when (code) {
        "NORMAL" -> RingerMode.NORMAL
        "VIBRATE" -> RingerMode.VIBRATE
        "SILENT" -> RingerMode.SILENT
        else -> null
    }

    /** For [com.ritmute.core.domain.model.AudioSnapshot], where the mode is not nullable. */
    fun ringerModeOrNormal(code: String?): RingerMode = ringerMode(code) ?: RingerMode.NORMAL

    // ── DndMode ──────────────────────────────────────────────────────────────

    fun code(value: DndMode): String = when (value) {
        // Persisted as RELEASE, not OFF: from Android 15 an app can only release its own
        // zen rule, never switch off the global filter. See docs/02, decision D-C1.
        DndMode.RELEASE -> "RELEASE"
        DndMode.PRIORITY -> "PRIORITY"
        DndMode.ALARMS_ONLY -> "ALARMS_ONLY"
        DndMode.TOTAL_SILENCE -> "TOTAL_SILENCE"
    }

    fun dndMode(code: String?): DndMode? = when (code) {
        "RELEASE" -> DndMode.RELEASE
        "PRIORITY" -> DndMode.PRIORITY
        "ALARMS_ONLY" -> DndMode.ALARMS_ONLY
        "TOTAL_SILENCE" -> DndMode.TOTAL_SILENCE
        else -> null
    }

    // ── DND sender policies ──────────────────────────────────────────────────

    fun code(value: CallPolicy): String = when (value) {
        CallPolicy.NONE -> "NONE"
        CallPolicy.STARRED -> "STARRED"
        CallPolicy.CONTACTS -> "CONTACTS"
        CallPolicy.ANY -> "ANY"
    }

    /** Unknown ⇒ [CallPolicy.NONE]: letting *fewer* people through is the safe failure. */
    fun callPolicy(code: String?): CallPolicy = when (code) {
        "NONE" -> CallPolicy.NONE
        "STARRED" -> CallPolicy.STARRED
        "CONTACTS" -> CallPolicy.CONTACTS
        "ANY" -> CallPolicy.ANY
        else -> CallPolicy.NONE
    }

    fun code(value: MessagePolicy): String = when (value) {
        MessagePolicy.NONE -> "NONE"
        MessagePolicy.STARRED -> "STARRED"
        MessagePolicy.CONTACTS -> "CONTACTS"
        MessagePolicy.ANY -> "ANY"
    }

    fun messagePolicy(code: String?): MessagePolicy = when (code) {
        "NONE" -> MessagePolicy.NONE
        "STARRED" -> MessagePolicy.STARRED
        "CONTACTS" -> MessagePolicy.CONTACTS
        "ANY" -> MessagePolicy.ANY
        else -> MessagePolicy.NONE
    }

    fun code(value: ConversationPolicy): String = when (value) {
        ConversationPolicy.NONE -> "NONE"
        ConversationPolicy.IMPORTANT -> "IMPORTANT"
        ConversationPolicy.ANYONE -> "ANYONE"
    }

    fun conversationPolicy(code: String?): ConversationPolicy = when (code) {
        "NONE" -> ConversationPolicy.NONE
        "IMPORTANT" -> ConversationPolicy.IMPORTANT
        "ANYONE" -> ConversationPolicy.ANYONE
        else -> ConversationPolicy.NONE
    }

    // ── ProfileTemplate ──────────────────────────────────────────────────────

    fun code(value: ProfileTemplate): String = when (value) {
        ProfileTemplate.NIGHT -> "NIGHT"
        ProfileTemplate.WORK -> "WORK"
        ProfileTemplate.MEETING -> "MEETING"
        ProfileTemplate.CINEMA -> "CINEMA"
        ProfileTemplate.DRIVING -> "DRIVING"
        ProfileTemplate.WEEKEND -> "WEEKEND"
    }

    /** Unknown ⇒ `null`, i.e. "a profile of no known template". Purely cosmetic loss. */
    fun profileTemplate(code: String?): ProfileTemplate? = when (code) {
        "NIGHT" -> ProfileTemplate.NIGHT
        "WORK" -> ProfileTemplate.WORK
        "MEETING" -> ProfileTemplate.MEETING
        "CINEMA" -> ProfileTemplate.CINEMA
        "DRIVING" -> ProfileTemplate.DRIVING
        "WEEKEND" -> ProfileTemplate.WEEKEND
        else -> null
    }

    // ── Activity log ─────────────────────────────────────────────────────────

    fun code(value: LogType): String = when (value) {
        LogType.APPLY -> "APPLY"
        LogType.RESTORE -> "RESTORE"
        LogType.SKIP -> "SKIP"
        LogType.ERROR -> "ERROR"
        LogType.SYSTEM -> "SYSTEM"
        LogType.PERMISSION -> "PERMISSION"
    }

    /**
     * Unknown ⇒ [LogType.SYSTEM]. A row we cannot classify is, by definition, something
     * the system did that this build does not know about; showing it under "system"
     * keeps the history honest instead of crashing the screen that exists to explain
     * things (CU-07).
     */
    fun logType(code: String?): LogType = when (code) {
        "APPLY" -> LogType.APPLY
        "RESTORE" -> LogType.RESTORE
        "SKIP" -> LogType.SKIP
        "ERROR" -> LogType.ERROR
        "SYSTEM" -> LogType.SYSTEM
        "PERMISSION" -> LogType.PERMISSION
        else -> LogType.SYSTEM
    }

    fun code(value: LogReason): String = when (value) {
        LogReason.SCHEDULE_START -> "SCHEDULE_START"
        LogReason.SCHEDULE_END -> "SCHEDULE_END"
        LogReason.MANUAL_ACTIVATION -> "MANUAL_ACTIVATION"
        LogReason.MANUAL_EXPIRED -> "MANUAL_EXPIRED"
        LogReason.GLOBAL_PAUSE_START -> "GLOBAL_PAUSE_START"
        LogReason.GLOBAL_PAUSE_END -> "GLOBAL_PAUSE_END"
        LogReason.BOOT_RECONCILE -> "BOOT_RECONCILE"
        LogReason.LOCKED_BOOT_RECONCILE -> "LOCKED_BOOT_RECONCILE"
        LogReason.WATCHDOG_REPAIR -> "WATCHDOG_REPAIR"
        LogReason.TIME_CHANGED -> "TIME_CHANGED"
        LogReason.TIMEZONE_CHANGED -> "TIMEZONE_CHANGED"
        LogReason.APP_UPDATED -> "APP_UPDATED"
        LogReason.PERMISSION_LOST -> "PERMISSION_LOST"
        LogReason.PERMISSION_GRANTED -> "PERMISSION_GRANTED"
        LogReason.SKIPPED_IN_CALL -> "SKIPPED_IN_CALL"
        LogReason.SKIPPED_MEDIA_PLAYING -> "SKIPPED_MEDIA_PLAYING"
        LogReason.SKIPPED_STALE_BASELINE -> "SKIPPED_STALE_BASELINE"
        LogReason.SECURITY_EXCEPTION -> "SECURITY_EXCEPTION"
        LogReason.SILENTLY_IGNORED_BY_SYSTEM -> "SILENTLY_IGNORED_BY_SYSTEM"
        LogReason.VOLUME_FIXED_DEVICE -> "VOLUME_FIXED_DEVICE"
        LogReason.ZEN_RULE_LIMIT_REACHED -> "ZEN_RULE_LIMIT_REACHED"
        LogReason.ZEN_RULE_OVERRIDDEN -> "ZEN_RULE_OVERRIDDEN"
        LogReason.FORCE_STOP_DETECTED -> "FORCE_STOP_DETECTED"
        LogReason.IMPORT_APPLIED -> "IMPORT_APPLIED"
        LogReason.PROFILE_UNCHANGED -> "PROFILE_UNCHANGED"
    }

    /**
     * Unknown ⇒ [LogReason.APP_UPDATED]. A reason code this build cannot read was
     * written by a different build, which is literally what that reason means; the UI
     * renders a sentence the user can act on rather than an empty row.
     */
    fun logReason(code: String?): LogReason = when (code) {
        "SCHEDULE_START" -> LogReason.SCHEDULE_START
        "SCHEDULE_END" -> LogReason.SCHEDULE_END
        "MANUAL_ACTIVATION" -> LogReason.MANUAL_ACTIVATION
        "MANUAL_EXPIRED" -> LogReason.MANUAL_EXPIRED
        "GLOBAL_PAUSE_START" -> LogReason.GLOBAL_PAUSE_START
        "GLOBAL_PAUSE_END" -> LogReason.GLOBAL_PAUSE_END
        "BOOT_RECONCILE" -> LogReason.BOOT_RECONCILE
        "LOCKED_BOOT_RECONCILE" -> LogReason.LOCKED_BOOT_RECONCILE
        "WATCHDOG_REPAIR" -> LogReason.WATCHDOG_REPAIR
        "TIME_CHANGED" -> LogReason.TIME_CHANGED
        "TIMEZONE_CHANGED" -> LogReason.TIMEZONE_CHANGED
        "APP_UPDATED" -> LogReason.APP_UPDATED
        "PERMISSION_LOST" -> LogReason.PERMISSION_LOST
        "PERMISSION_GRANTED" -> LogReason.PERMISSION_GRANTED
        "SKIPPED_IN_CALL" -> LogReason.SKIPPED_IN_CALL
        "SKIPPED_MEDIA_PLAYING" -> LogReason.SKIPPED_MEDIA_PLAYING
        "SKIPPED_STALE_BASELINE" -> LogReason.SKIPPED_STALE_BASELINE
        "SECURITY_EXCEPTION" -> LogReason.SECURITY_EXCEPTION
        "SILENTLY_IGNORED_BY_SYSTEM" -> LogReason.SILENTLY_IGNORED_BY_SYSTEM
        "VOLUME_FIXED_DEVICE" -> LogReason.VOLUME_FIXED_DEVICE
        "ZEN_RULE_LIMIT_REACHED" -> LogReason.ZEN_RULE_LIMIT_REACHED
        "ZEN_RULE_OVERRIDDEN" -> LogReason.ZEN_RULE_OVERRIDDEN
        "FORCE_STOP_DETECTED" -> LogReason.FORCE_STOP_DETECTED
        "IMPORT_APPLIED" -> LogReason.IMPORT_APPLIED
        "PROFILE_UNCHANGED" -> LogReason.PROFILE_UNCHANGED
        else -> LogReason.APP_UPDATED
    }

    // ── Days of week ─────────────────────────────────────────────────────────
    //
    // Only the backup file uses names; the database stores the bitmask (amendment E-02).

    fun code(value: DayOfWeek): String = when (value) {
        DayOfWeek.MONDAY -> "MONDAY"
        DayOfWeek.TUESDAY -> "TUESDAY"
        DayOfWeek.WEDNESDAY -> "WEDNESDAY"
        DayOfWeek.THURSDAY -> "THURSDAY"
        DayOfWeek.FRIDAY -> "FRIDAY"
        DayOfWeek.SATURDAY -> "SATURDAY"
        DayOfWeek.SUNDAY -> "SUNDAY"
    }

    fun dayOfWeek(code: String?): DayOfWeek? = when (code) {
        "MONDAY" -> DayOfWeek.MONDAY
        "TUESDAY" -> DayOfWeek.TUESDAY
        "WEDNESDAY" -> DayOfWeek.WEDNESDAY
        "THURSDAY" -> DayOfWeek.THURSDAY
        "FRIDAY" -> DayOfWeek.FRIDAY
        "SATURDAY" -> DayOfWeek.SATURDAY
        "SUNDAY" -> DayOfWeek.SUNDAY
        else -> null
    }

    /** Bitmask to ISO day names, always in Monday-first order so exports are stable. */
    fun daysMaskToCodes(mask: Int): List<String> =
        DayMask.toSet(mask).map { code(it) }

    /**
     * Day names back to a bitmask. Returns the mask plus every name that was not
     * understood, so the importer can report them instead of silently dropping days.
     */
    fun codesToDaysMask(codes: List<String>): DaysMaskDecoding {
        var mask = DayMask.NONE
        val unknown = mutableListOf<String>()
        for (raw in codes) {
            val day = dayOfWeek(raw)
            if (day == null) unknown += raw else mask = mask or DayMask.bit(day)
        }
        return DaysMaskDecoding(mask, unknown)
    }

    data class DaysMaskDecoding(val mask: Int, val unknownCodes: List<String>)
}
