package com.ritmute.core.system.audio

import android.app.NotificationManager
import android.media.AudioManager
import android.os.Build
import com.ritmute.core.domain.model.AudioStream
import com.ritmute.core.domain.model.StreamLevel
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this particular device will and will not let us do with volume.
 *
 * On a coupled device, writing one of them moves the other; a profile that sets
 * `ring = 80` and `notification = 20` is then impossible to satisfy, and the app must say
 * so rather than write twice and leave the user with whichever value landed last.
 */
enum class StreamCoupling {
    /** Observed proof that RING and NOTIFICATION move independently. */
    INDEPENDENT,

    /** Observed proof that writing one moved the other. */
    COUPLED,

    /**
     * Not decidable without writing.
     *
     * v1.0 uses only the non-destructive heuristic plus evidence collected from writes the
     * user asked for anyway. The destructive probe (write, read, restore) was cut from
     * scope, so `UNKNOWN` is a first-class answer the UI must render honestly.
     * See docs/02, section 8.
     */
    UNKNOWN,
}

/** Maps the domain's device-independent stream vocabulary onto `AudioManager` constants. */
internal object AndroidStreams {

    fun idOf(stream: AudioStream): Int = when (stream) {
        AudioStream.VOICE_CALL -> AudioManager.STREAM_VOICE_CALL
        AudioStream.SYSTEM -> AudioManager.STREAM_SYSTEM
        AudioStream.RING -> AudioManager.STREAM_RING
        AudioStream.MUSIC -> AudioManager.STREAM_MUSIC
        AudioStream.ALARM -> AudioManager.STREAM_ALARM
        AudioStream.NOTIFICATION -> AudioManager.STREAM_NOTIFICATION
        AudioStream.ACCESSIBILITY -> AudioManager.STREAM_ACCESSIBILITY
    }

    /**
     * Flags for every volume write in this app: **always zero**.
     *
     * `FLAG_SHOW_UI` would pop the system volume panel over whatever the user is doing at
     * 23:00, and `FLAG_PLAY_SOUND` would make a *silencing* app emit a beep. Both are
     * exactly the behaviour users complain about in the competition.
     */
    const val NO_FLAGS = 0
}

interface AudioCapabilities {

    /**
     * True on devices with a fixed output level (car head units, some TV boxes).
     * Every volume write is a documented no-op there, so we refuse up front instead of
     * writing six times and reporting six silent failures.
     */
    fun isVolumeFixed(): Boolean

    /**
     * Whether the app holds `ACCESS_NOTIFICATION_POLICY`.
     *
     * It gates far more than Do Not Disturb: with DND active, `setStreamVolume` on the
     * ring-aliased streams and `setRingerMode` into or out of silent both throw
     * `SecurityException` without it. Both audio controllers therefore consult it.
     */
    fun isNotificationPolicyAccessGranted(): Boolean

    /**
     * `getStreamMinVolume` (API 28+). Below that, a hard-coded fallback: 1 for
     * `STREAM_VOICE_CALL` — whose real minimum is 1 on every known device and whose index
     * 0 is silently ignored — and 0 for everything else.
     */
    fun minIndex(stream: AudioStream): Int

    fun maxIndex(stream: AudioStream): Int

    fun currentIndex(stream: AudioStream): Int

    /**
     * Whether the platform currently has the stream muted.
     *
     * Needed because [currentIndex] alone cannot tell "the write was ignored" from "the
     * write succeeded by silencing the stream". Devices differ on what `getStreamVolume`
     * reports for a muted stream: some return 0, others keep returning the level they will
     * restore on unmute. On the second kind, a request for zero looks exactly like a
     * swallowed write, and the app would accuse the phone of lying when it had obeyed.
     */
    fun isMuted(stream: AudioStream): Boolean

    fun levelOf(stream: AudioStream): StreamLevel

    fun isWritable(stream: AudioStream): Boolean

    /** Current verdict on RING/NOTIFICATION aliasing. Never performs a write. */
    fun ringNotificationCoupling(): StreamCoupling

    /**
     * Feeds evidence gathered while performing a write the profile requested anyway.
     * Called by [VolumeController]; nothing else should need it.
     */
    fun recordCouplingEvidence(coupled: Boolean)
}

@Singleton
class AudioCapabilitiesImpl @Inject constructor(
    private val audioManager: AudioManager,
    private val notificationManager: NotificationManager,
) : AudioCapabilities {

    /**
     * Evidence survives for the life of the process only. Persisting it would mean
     * carrying a stale verdict across an OTA that changed the device's aliasing, which is
     * precisely the kind of quiet wrongness this app exists to avoid.
     */
    private val couplingEvidence = AtomicReference(StreamCoupling.UNKNOWN)

    override fun isVolumeFixed(): Boolean = audioManager.isVolumeFixed

    override fun isNotificationPolicyAccessGranted(): Boolean =
        notificationManager.isNotificationPolicyAccessGranted

    override fun minIndex(stream: AudioStream): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            audioManager.getStreamMinVolume(AndroidStreams.idOf(stream))
        } else if (stream == AudioStream.VOICE_CALL) {
            LEGACY_VOICE_CALL_MIN
        } else {
            0
        }

    override fun maxIndex(stream: AudioStream): Int =
        audioManager.getStreamMaxVolume(AndroidStreams.idOf(stream))

    override fun currentIndex(stream: AudioStream): Int =
        audioManager.getStreamVolume(AndroidStreams.idOf(stream))

    override fun isMuted(stream: AudioStream): Boolean =
        audioManager.isStreamMute(AndroidStreams.idOf(stream))

    override fun levelOf(stream: AudioStream): StreamLevel = StreamLevel(
        steps = currentIndex(stream),
        minSteps = minIndex(stream),
        maxSteps = maxIndex(stream),
    )

    override fun isWritable(stream: AudioStream): Boolean = stream.writable && !isVolumeFixed()

    override fun ringNotificationCoupling(): StreamCoupling {
        val observed = couplingEvidence.get()
        if (observed != StreamCoupling.UNKNOWN) return observed

        // Non-destructive heuristic. Different scales prove independence; identical scales
        // and identical current values prove nothing at all, because two independent
        // streams can perfectly well happen to sit at the same index.
        val ringMax = maxIndex(AudioStream.RING)
        val notificationMax = maxIndex(AudioStream.NOTIFICATION)
        if (ringMax != notificationMax) return StreamCoupling.INDEPENDENT

        val ringNow = currentIndex(AudioStream.RING)
        val notificationNow = currentIndex(AudioStream.NOTIFICATION)
        return if (ringNow != notificationNow) StreamCoupling.INDEPENDENT else StreamCoupling.UNKNOWN
    }

    override fun recordCouplingEvidence(coupled: Boolean) {
        couplingEvidence.set(if (coupled) StreamCoupling.COUPLED else StreamCoupling.INDEPENDENT)
    }

    private companion object {
        const val LEGACY_VOICE_CALL_MIN = 1
    }
}
