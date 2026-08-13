package com.sonoritmo.core.system.audio

import android.media.AudioManager
import com.sonoritmo.core.domain.logic.VolumeMath
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.VolumeSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import javax.inject.Inject
import javax.inject.Singleton

interface VolumeController {

    /** Writes a native index. Verifies by reading back. Never throws. */
    fun setIndex(stream: AudioStream, index: Int): AudioOpResult

    /**
     * Writes a percentage, converted with [VolumeMath] using this device's real
     * `min`/`max` for the stream.
     *
     * @throws IllegalArgumentException if [percent] is outside 0..100 — that is a
     *   programmer error, not a platform refusal, and the domain already validates it.
     */
    fun setPercent(stream: AudioStream, percent: Int): AudioOpResult

    /**
     * A linear ramp from the current index to [targetIndex], spread over
     * [totalSeconds], emitting the result of every individual write.
     *
     * Returned as a cold [Flow] rather than a `suspend fun` for two reasons. The caller
     * must be able to observe partial progress — a ramp that is refused at step 2 of 7 has
     * still moved the volume and the activity log has to say so. And it must be
     * cancellable at any step: the ramp runs inside an ephemeral foreground service that
     * can be killed, and `delay` makes every step a cancellation point for free.
     *
     * The number of steps comes from `VolumeMath.rampIndices`, i.e. from the device's real
     * step count, never from the number of seconds: a 15 s ramp on a 7-step device is 7
     * changes, not 15.
     */
    fun ramp(stream: AudioStream, targetIndex: Int, totalSeconds: Int): Flow<AudioOpResult>
}

@Singleton
class VolumeControllerImpl @Inject constructor(
    private val audioManager: AudioManager,
    private val capabilities: AudioCapabilities,
) : VolumeController {

    // setStreamVolume's flags parameter is a flag IntDef, and lint reads it as "one or more
    // of", with no way to express the absence of every flag. Zero is the deliberate value
    // here and the reason is written on AndroidStreams.NO_FLAGS: FLAG_SHOW_UI would throw
    // the volume panel over whatever the user is doing, and FLAG_PLAY_SOUND would make a
    // silencing app beep.
    @Suppress("WrongConstant")
    override fun setIndex(stream: AudioStream, index: Int): AudioOpResult {
        if (!stream.writable) return AudioOpResult.Refused(RefusalReason.STREAM_NOT_WRITABLE)
        if (capabilities.isVolumeFixed()) {
            return AudioOpResult.Refused(RefusalReason.VOLUME_FIXED_DEVICE)
        }

        val min = capabilities.minIndex(stream)
        val max = capabilities.maxIndex(stream)
        val target = index.coerceIn(min, max)
        val clampReason = when {
            index < min -> ClampReason.DEVICE_MINIMUM
            index > max -> ClampReason.DEVICE_MAXIMUM
            else -> null
        }

        val streamId = AndroidStreams.idOf(stream)
        val before = capabilities.currentIndex(stream)
        val sibling = ringAliasSibling(stream)
        val siblingBefore = sibling?.let { capabilities.currentIndex(it) }

        try {
            audioManager.setStreamVolume(streamId, target, AndroidStreams.NO_FLAGS)
        } catch (security: SecurityException) {
            // Thrown when Do Not Disturb is active and we lack ACCESS_NOTIFICATION_POLICY.
            // Expected on a device where the user revoked the access after onboarding.
            return AudioOpResult.Refused(RefusalReason.SECURITY_EXCEPTION)
        }

        // The verification that gives this module its value. See docs/02, section 5.6.
        val after = capabilities.currentIndex(stream)
        recordCouplingEvidence(sibling, siblingBefore, before, after)

        return when {
            after == target && clampReason != null ->
                AudioOpResult.Clamped(index, after, clampReason)

            after == target -> AudioOpResult.Applied(after)

            // The write moved the volume but the platform settled somewhere else: the
            // safe-listening cap on STREAM_MUSIC is the usual explanation. It is a clamp,
            // not a failure — the device did change.
            after != before -> AudioOpResult.Clamped(index, after, ClampReason.SAFE_MEDIA_VOLUME)

            else -> AudioOpResult.SilentlyIgnored(requestedIndex = target, observedIndex = after)
        }
    }

    override fun setPercent(stream: AudioStream, percent: Int): AudioOpResult {
        require(percent in VolumeSettings.MIN_PERCENT..VolumeSettings.MAX_PERCENT) {
            "percent must be 0..100, was $percent"
        }
        if (!stream.writable) return AudioOpResult.Refused(RefusalReason.STREAM_NOT_WRITABLE)
        val index = VolumeMath.percentToIndex(
            percent = percent,
            minSteps = capabilities.minIndex(stream),
            maxSteps = capabilities.maxIndex(stream),
        )
        return setIndex(stream, index)
    }

    override fun ramp(stream: AudioStream, targetIndex: Int, totalSeconds: Int): Flow<AudioOpResult> = flow {
        if (!stream.writable) {
            emit(AudioOpResult.Refused(RefusalReason.STREAM_NOT_WRITABLE))
            return@flow
        }
        val min = capabilities.minIndex(stream)
        val max = capabilities.maxIndex(stream)
        val target = targetIndex.coerceIn(min, max)
        val from = capabilities.currentIndex(stream)
        val steps = VolumeMath.rampIndices(from, target)

        if (totalSeconds <= 0 || steps.isEmpty()) {
            // Nothing to spread over time: a single write is the whole ramp.
            emit(setIndex(stream, targetIndex))
            return@flow
        }

        val stepDelayMs = (totalSeconds * 1_000L / steps.size).coerceAtLeast(MIN_STEP_DELAY_MS)
        for (next in steps) {
            // Delay *before* each step so the ramp finishes on the target at
            // approximately totalSeconds rather than one step early.
            delay(stepDelayMs)
            val result = setIndex(stream, next)
            emit(result)
            // A refusal or a swallowed write will not fix itself on the next step; carrying
            // on would produce one identical log line per step for no benefit.
            if (result is AudioOpResult.Refused || result is AudioOpResult.SilentlyIgnored) return@flow
        }
    }

    /**
     * RING and NOTIFICATION are aliased on most devices. Writing one and reading the other
     * is a free, non-destructive coupling probe, since the write was requested anyway.
     */
    private fun ringAliasSibling(stream: AudioStream): AudioStream? = when (stream) {
        AudioStream.RING -> AudioStream.NOTIFICATION
        AudioStream.NOTIFICATION -> AudioStream.RING
        else -> null
    }

    private fun recordCouplingEvidence(
        sibling: AudioStream?,
        siblingBefore: Int?,
        before: Int,
        after: Int,
    ) {
        if (sibling == null || siblingBefore == null) return
        val siblingAfter = capabilities.currentIndex(sibling)
        when {
            // The sibling moved although we never wrote to it: aliased, beyond doubt.
            siblingAfter != siblingBefore -> capabilities.recordCouplingEvidence(coupled = true)
            // We moved, the sibling did not, and it was not already sitting on our new
            // value (which would make "did not move" meaningless): independent.
            after != before && siblingBefore != after -> capabilities.recordCouplingEvidence(coupled = false)
            else -> Unit // Inconclusive. Leave the previous verdict untouched.
        }
    }

    private companion object {
        /**
         * A floor on the pace of a ramp. Below roughly this interval the changes stop being
         * perceived as a fade and start being perceived as a glitch, and every write costs
         * a binder round trip plus a read-back.
         */
        const val MIN_STEP_DELAY_MS = 120L
    }
}
