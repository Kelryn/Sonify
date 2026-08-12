package com.sonoritmo.core.domain.model

import java.time.Instant

/** A stream's native level, exactly as the device reports it. */
data class StreamLevel(
    val steps: Int,
    val minSteps: Int,
    val maxSteps: Int,
)

/**
 * The audio state of the device at a point in time, used to put things back.
 *
 * ## Why native steps and not percentages
 *
 * On a 7-step device, `steps = 3` is 42.857 %, stored as 43, restored as
 * `round(0.43 × 7) = 3`. That works by a hair. Combine it with `getStreamMinVolume`
 * (1 for VOICE_CALL) and 15-step devices and the round trip stops being exact.
 * Restoring must return **exactly** what was there. Percentages are for the user's
 * portable configuration; native steps are for faithful restoration. Two different
 * concepts. See docs/02, amendment E-06.
 *
 * ## Single baseline, not a stack
 *
 * There is exactly one live snapshot at a time, captured when automation goes from
 * "nothing active" to "something active", and consumed when it goes back to
 * "nothing active". Profile→profile transitions capture nothing.
 *
 * A stack of nested snapshots corrupts itself the moment the process is killed
 * mid-transition — which is the normal case on battery-aggressive OEMs. With a single
 * baseline, a cold start with a baseline present and no active window is trivially
 * recoverable: restore it and delete it. See docs/02, amendment E-07.
 */
data class AudioSnapshot(
    val capturedAt: Instant,
    val levels: Map<AudioStream, StreamLevel>,
    val ringerMode: RingerMode,
    /**
     * `NotificationManager.INTERRUPTION_FILTER_*` at capture time.
     *
     * Diagnostic only — never restored. From Android 15 the global filter is readable
     * but not writable by apps, so pretending we can put it back would be a lie.
     * See docs/02, decision D-C1.
     */
    val interruptionFilter: Int,
    /** Which profile's activation caused the capture, for staleness checks. */
    val ownerProfileUuid: String? = null,
) {
    /**
     * A baseline older than this is treated as stale and discarded rather than applied.
     *
     * Scenario it protects against: the phone is off from 22:00 to 09:00 with a
     * 23:00–07:00 window and `restoreOnExit`. At 09:00 there is nothing coherent left
     * to restore, and forcing an 11-hour-old state on the user would be a bug, not a
     * feature.
     */
    fun isStaleAt(now: Instant, maxAge: java.time.Duration = MAX_AGE): Boolean =
        java.time.Duration.between(capturedAt, now) > maxAge

    companion object {
        val MAX_AGE: java.time.Duration = java.time.Duration.ofHours(26)
    }
}
