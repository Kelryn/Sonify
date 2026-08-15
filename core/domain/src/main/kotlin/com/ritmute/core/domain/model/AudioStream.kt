package com.ritmute.core.domain.model

/**
 * The audio streams RitMute knows about.
 *
 * Note [ACCESSIBILITY]: `AudioManager.setStreamVolume(STREAM_ACCESSIBILITY, ...)` is a
 * *silent no-op* for any app that does not hold the signature-level permission
 * `CHANGE_ACCESSIBILITY_VOLUME`. AOSP returns without raising anything at all, so a
 * `try/catch` cannot detect it. We therefore treat the stream as read-only: it is
 * captured in snapshots and shown in the activity log, but never written.
 * See docs/02, decision D-C2.
 */
enum class AudioStream(val writable: Boolean) {
    RING(writable = true),
    NOTIFICATION(writable = true),
    MUSIC(writable = true),
    ALARM(writable = true),
    SYSTEM(writable = true),

    /**
     * Writable, but index 0 requires `MODIFY_PHONE_STATE` and is silently ignored
     * without it. Callers must clamp to the device minimum instead of writing 0.
     */
    VOICE_CALL(writable = true),

    ACCESSIBILITY(writable = false),
    ;

    companion object {
        /** Streams a profile is allowed to configure. */
        val WRITABLE: List<AudioStream> = entries.filter { it.writable }

        /**
         * Streams that are aliased to STREAM_RING on most devices. Writing any of them
         * can move the others and can flip the ringer mode as a side effect, so the
         * apply order has to be deterministic. See docs/02, section 5.6.
         */
        val RING_ALIASED: Set<AudioStream> = setOf(RING, NOTIFICATION, SYSTEM)
    }
}
