package com.sonoritmo.core.domain.model

/**
 * Desired volume per stream, expressed as a **percentage 0..100**.
 *
 * `null` means "do not touch". This is the single convention for "unchanged"
 * across the whole model (docs/02, amendment E-03) — there are no sentinel enum
 * values anywhere.
 *
 * Percentages rather than native steps: the number of steps per stream varies by
 * device (7, 15, 25, 30...), so storing steps makes a configuration unportable and
 * reproduces the "volume increment limit" complaint users make about the
 * competition. Conversion to native steps happens at apply time, in
 * [com.sonoritmo.core.domain.logic.VolumeMath].
 *
 * Note this is *not* the type used for snapshots: restoring must be bit-exact, so
 * [AudioSnapshot] stores native steps instead. See docs/02, amendment E-06.
 */
data class VolumeSettings(
    val ring: Int? = null,
    val notification: Int? = null,
    val music: Int? = null,
    val alarm: Int? = null,
    val system: Int? = null,
    val voiceCall: Int? = null,
) {
    operator fun get(stream: AudioStream): Int? = when (stream) {
        AudioStream.RING -> ring
        AudioStream.NOTIFICATION -> notification
        AudioStream.MUSIC -> music
        AudioStream.ALARM -> alarm
        AudioStream.SYSTEM -> system
        AudioStream.VOICE_CALL -> voiceCall
        AudioStream.ACCESSIBILITY -> null
    }

    fun with(stream: AudioStream, percent: Int?): VolumeSettings = when (stream) {
        AudioStream.RING -> copy(ring = percent)
        AudioStream.NOTIFICATION -> copy(notification = percent)
        AudioStream.MUSIC -> copy(music = percent)
        AudioStream.ALARM -> copy(alarm = percent)
        AudioStream.SYSTEM -> copy(system = percent)
        AudioStream.VOICE_CALL -> copy(voiceCall = percent)
        AudioStream.ACCESSIBILITY -> this
    }

    /** Only the streams this profile actually wants to change, in a stable order. */
    fun requested(): List<Pair<AudioStream, Int>> =
        AudioStream.WRITABLE.mapNotNull { stream -> this[stream]?.let { stream to it } }

    val isEmpty: Boolean get() = requested().isEmpty()

    companion object {
        val UNCHANGED = VolumeSettings()

        const val MIN_PERCENT = 0
        const val MAX_PERCENT = 100
    }
}
