package com.sonoritmo.core.domain.model

/**
 * Per-profile behaviour switches.
 *
 * Note there is no `gradualTransition: Boolean`: `transitionSeconds == 0` **is**
 * "no ramp". One field fewer, one impossible state fewer (docs/02, amendment E-04).
 */
data class ProfileOptions(
    /**
     * Restore the captured baseline when automation stops, instead of leaving the
     * profile's values in place. See [AudioSnapshot] for the single-baseline policy.
     */
    val restoreOnExit: Boolean = true,

    /**
     * Linear volume ramp length, in seconds.
     *
     * Capped at [MAX_TRANSITION_SECONDS] rather than the 60 s of the original spec:
     * a ramp runs inside an ephemeral foreground service started from the alarm
     * receiver, and on a device with 7 volume steps a 60 s ramp is 7 audible jumps
     * spread over a minute, not a smooth fade. It is also disabled automatically when
     * the app is running in degraded (inexact alarm) mode, because the
     * exact-alarm exemption for starting a foreground service from the background
     * does not apply there.
     */
    val transitionSeconds: Int = 0,

    /**
     * Skip applying while a call is in progress.
     *
     * Detected with `AudioManager.getMode()`, which needs **no permission** and covers
     * VoIP as well as telephony. `READ_PHONE_STATE` was removed from the project for
     * this reason (docs/02, amendment E-15).
     */
    val skipDuringCall: Boolean = true,

    /** Do not lower STREAM_MUSIC while something is actually playing. */
    val skipIfMediaPlaying: Boolean = false,

    /** Post a notification when this profile is applied. Requires POST_NOTIFICATIONS. */
    val notifyOnApply: Boolean = false,
) {
    companion object {
        const val MAX_TRANSITION_SECONDS = 15
        val DEFAULT = ProfileOptions()
    }
}
