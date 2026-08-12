package com.sonoritmo.core.system.audio

import com.sonoritmo.core.domain.model.LogReason

/**
 * Why the platform (or this module, on the platform's behalf) declined to perform an
 * operation.
 *
 * Every one of these is an **expected** outcome, not a bug: a device without notification
 * policy access, a car head unit with a fixed volume, a stream that is read-only for
 * third-party apps. They are returned as values rather than thrown, because a refusal is
 * information the user is entitled to see in the activity log — an exception would either
 * be swallowed by a `catch` somewhere or crash the app for a condition the user can fix
 * from Settings. See docs/02, section 5.7.
 */
enum class RefusalReason {
    /** `NotificationManager.isNotificationPolicyAccessGranted()` is false. */
    NO_NOTIFICATION_POLICY_ACCESS,

    /**
     * `AudioManager.isVolumeFixed()` is true: a fixed-output device (many car head units,
     * some TV boxes) where every volume write is a no-op by design.
     */
    VOLUME_FIXED_DEVICE,

    /**
     * The stream cannot be written by a normal app at all — currently only
     * `STREAM_ACCESSIBILITY`, which needs the signature permission
     * `CHANGE_ACCESSIBILITY_VOLUME`. See docs/02, decision D-C2.
     */
    STREAM_NOT_WRITABLE,

    /** The platform threw `SecurityException`. Caught here, never propagated. */
    SECURITY_EXCEPTION,

    /** The API needed does not exist on this device's API level, and there is no fallback. */
    UNSUPPORTED_API_LEVEL,

    /** A call is in progress and the profile asked us not to interfere. */
    CALL_IN_PROGRESS,

    /** Media is playing and the profile asked us not to turn it down. */
    MEDIA_PLAYING,

    /**
     * We already own the defensive maximum of `AutomaticZenRule`s. The platform cap is
     * 100 per package; we stop at 90 so that a corrupt state can still be repaired.
     * See docs/02, amendment E-17.
     */
    ZEN_RULE_LIMIT_REACHED,

    /** A system service was unavailable (`getSystemService` returned null). Very rare. */
    SERVICE_UNAVAILABLE,
    ;

    /**
     * The log line this refusal produces. Keeping the mapping here means :app never has
     * to translate platform failures into user-facing vocabulary.
     */
    val logReason: LogReason
        get() = when (this) {
            NO_NOTIFICATION_POLICY_ACCESS -> LogReason.PERMISSION_LOST
            VOLUME_FIXED_DEVICE -> LogReason.VOLUME_FIXED_DEVICE
            STREAM_NOT_WRITABLE -> LogReason.SILENTLY_IGNORED_BY_SYSTEM
            SECURITY_EXCEPTION -> LogReason.SECURITY_EXCEPTION
            UNSUPPORTED_API_LEVEL -> LogReason.SILENTLY_IGNORED_BY_SYSTEM
            CALL_IN_PROGRESS -> LogReason.SKIPPED_IN_CALL
            MEDIA_PLAYING -> LogReason.SKIPPED_MEDIA_PLAYING
            ZEN_RULE_LIMIT_REACHED -> LogReason.ZEN_RULE_LIMIT_REACHED
            SERVICE_UNAVAILABLE -> LogReason.SILENTLY_IGNORED_BY_SYSTEM
        }
}
