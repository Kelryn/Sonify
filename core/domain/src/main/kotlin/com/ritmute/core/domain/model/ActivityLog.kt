package com.ritmute.core.domain.model

import java.time.Instant

enum class LogType { APPLY, RESTORE, SKIP, ERROR, SYSTEM, PERMISSION }

/**
 * Why something happened.
 *
 * An enum rather than free text: a `String` reason means messages written in code
 * (which the spec forbids), untranslatable strings, and a history screen that cannot
 * be filtered reliably. The UI composes the sentence with
 * `stringResource(reason.labelRes, params)`. See docs/02, amendment E-09.
 */
enum class LogReason {
    SCHEDULE_START,
    SCHEDULE_END,
    MANUAL_ACTIVATION,
    MANUAL_EXPIRED,
    GLOBAL_PAUSE_START,
    GLOBAL_PAUSE_END,
    BOOT_RECONCILE,
    LOCKED_BOOT_RECONCILE,
    WATCHDOG_REPAIR,
    TIME_CHANGED,
    TIMEZONE_CHANGED,
    APP_UPDATED,
    PERMISSION_LOST,
    PERMISSION_GRANTED,
    SKIPPED_IN_CALL,
    SKIPPED_MEDIA_PLAYING,
    SKIPPED_STALE_BASELINE,
    SECURITY_EXCEPTION,
    SILENTLY_IGNORED_BY_SYSTEM,
    VOLUME_FIXED_DEVICE,
    ZEN_RULE_LIMIT_REACHED,
    ZEN_RULE_OVERRIDDEN,
    FORCE_STOP_DETECTED,
    IMPORT_APPLIED,
    PROFILE_UNCHANGED,
}

/**
 * One immutable line of the "why did my phone sound like that?" record (D6, CU-07).
 */
data class ActivityLogEntry(
    val id: Long = 0,
    val timestamp: Instant,
    /**
     * Zone and offset in force when it happened.
     *
     * CU-07 is literally "why did the sound change *at 3 in the morning*". If the user
     * travelled or the clock changed — both of which the spec explicitly handles — an
     * `Instant` alone cannot reconstruct the local time they actually saw.
     */
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val type: LogType,
    val reason: LogReason,
    /** Structured parameters for the localised message, e.g. `{"stream":"RING","to":0}`. */
    val paramsJson: String? = null,
    val profileUuid: String? = null,
    /**
     * Denormalised on purpose: an audit record must stay readable after the profile it
     * refers to is deleted. The only denormalisation in the schema, and a justified one.
     */
    val profileNameSnapshot: String? = null,
    val scheduleUuid: String? = null,
    val success: Boolean = true,
    /** Technical diagnostics only (exception class, stream name). Never shown as the headline. */
    val detail: String? = null,
) {
    companion object {
        /** Retention (RF-38): whichever limit bites first. */
        const val MAX_ENTRIES = 1_000
        const val MAX_AGE_DAYS = 30L
    }
}
