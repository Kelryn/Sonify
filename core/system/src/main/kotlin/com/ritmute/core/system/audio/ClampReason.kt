package com.ritmute.core.system.audio

/**
 * Why a requested index could not be honoured exactly.
 *
 * A clamp is a *success*: the write happened and the device is now in the closest legal
 * state to what the profile asked for. It is reported separately from [AudioOpResult.Applied]
 * only so the diagnostics screen can explain why "30 %" reads back as "33 %" on a device
 * with three steps, instead of leaving the user to conclude the app is broken.
 */
enum class ClampReason {
    /**
     * The target was below `getStreamMinVolume()`.
     *
     * The important case is `STREAM_VOICE_CALL`, whose minimum is 1 on essentially every
     * device: writing 0 there needs `MODIFY_PHONE_STATE` and is otherwise **ignored in
     * silence**, with no exception to catch. Clamping up front turns an invisible failure
     * into a visible, explainable one. See docs/02, decision D-C2.
     */
    DEVICE_MINIMUM,

    /** The target was above `getStreamMaxVolume()` for this stream. */
    DEVICE_MAXIMUM,

    /**
     * The device accepted the write but settled on a lower index than requested.
     *
     * Typically the EU/safe-listening cap on `STREAM_MUSIC` with headphones connected:
     * the platform silently limits the level. We detect it by reading back, and report a
     * clamp rather than a silent failure, because the write *did* take effect.
     */
    SAFE_MEDIA_VOLUME,
}
