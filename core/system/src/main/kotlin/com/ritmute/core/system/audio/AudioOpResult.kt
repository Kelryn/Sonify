package com.ritmute.core.system.audio

import com.ritmute.core.domain.model.LogReason

/**
 * The outcome of a single audio write.
 *
 * This module **never** lets a platform exception escape. Every expected refusal by the
 * system becomes one of these values; only genuine programmer errors (an out-of-range
 * percentage, a null system service where one is mandatory) raise
 * `IllegalArgumentException`. That is what makes RNF-09 testable instead of a matter of
 * scattered `try/catch` discipline. See docs/02, section 5.7.
 *
 * ## Why [SilentlyIgnored] exists at all
 *
 * It is the single most important member. `AudioManager.setStreamVolume` returns `void`
 * and, in at least five well-known situations, does nothing whatsoever *without throwing*:
 * `STREAM_ACCESSIBILITY` without the signature permission, `STREAM_VOICE_CALL` at index 0,
 * fixed-volume devices, OEM layers that revert `setRingerMode` a few milliseconds later,
 * and the Android 17 background-audio hardening. A `try/catch` catches none of them.
 * Reading the value back is the only defence that covers all five at once, and it is
 * mandatory for every write in this module. See docs/02, section 5.6.
 */
sealed interface AudioOpResult {

    /** The device now holds exactly what was requested. [index] is `null` for non-volume ops. */
    data class Applied(val index: Int? = null) : AudioOpResult

    /** The write succeeded, but at a different index than requested. Still a success. */
    data class Clamped(
        val requestedIndex: Int,
        val appliedIndex: Int,
        val reason: ClampReason,
    ) : AudioOpResult

    /** The operation was not attempted, or the platform rejected it in a detectable way. */
    data class Refused(val reason: RefusalReason) : AudioOpResult

    /**
     * The call returned normally and the device did not change. The write was swallowed.
     * [observedIndex] is what the device reports now; `null` for non-volume ops.
     */
    data class SilentlyIgnored(
        val requestedIndex: Int? = null,
        val observedIndex: Int? = null,
    ) : AudioOpResult

    /** True when the device ended up in the intended state, exactly or as close as legal. */
    val isSuccess: Boolean
        get() = this is Applied || this is Clamped

    /** Log vocabulary for this outcome, or `null` when there is nothing worth logging. */
    val logReason: LogReason?
        get() = when (this) {
            is Applied -> null
            is Clamped -> null
            is Refused -> reason.logReason
            is SilentlyIgnored -> LogReason.SILENTLY_IGNORED_BY_SYSTEM
        }
}
