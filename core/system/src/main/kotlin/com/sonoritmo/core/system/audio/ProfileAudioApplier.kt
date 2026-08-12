package com.sonoritmo.core.system.audio

import com.sonoritmo.core.domain.logic.VolumeMath
import com.sonoritmo.core.domain.model.AudioSnapshot
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.LogReason
import com.sonoritmo.core.domain.model.RingerMode
import com.sonoritmo.core.domain.model.SoundProfile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.lastOrNull
import javax.inject.Inject
import javax.inject.Singleton

enum class ApplyOutcome {
    /** Every requested write landed (exactly or clamped to a legal value). */
    APPLIED,

    /** Some writes landed and some did not. The most common failure on OEM layers. */
    PARTIAL,

    /** Nothing was attempted because the profile asked us to stand down. */
    SKIPPED,

    /** Nothing landed. Usually a fixed-volume device or revoked policy access. */
    REFUSED,

    /** The profile requested no audio change at all. */
    NO_CHANGE,
}

/**
 * Everything that happened during one application of a profile, in a form the activity log
 * and the diagnostics screen can render without re-deriving anything.
 */
data class ApplyReport(
    val outcome: ApplyOutcome,
    val streams: Map<AudioStream, AudioOpResult> = emptyMap(),
    val ringer: AudioOpResult? = null,
    val skipReason: LogReason? = null,
) {
    /** Streams the device pretended to change and did not. The headline of D6. */
    val silentlyIgnored: List<AudioStream>
        get() = streams.filterValues { it is AudioOpResult.SilentlyIgnored }.keys.toList()

    val success: Boolean
        get() = outcome == ApplyOutcome.APPLIED || outcome == ApplyOutcome.NO_CHANGE

    /** The single most relevant log reason, or `null` when everything went well. */
    val worstReason: LogReason?
        get() = skipReason
            ?: streams.values.firstNotNullOfOrNull { it.logReason }
            ?: ringer?.logReason
}

/** Difference between what a profile asks for and what the device currently holds. */
data class DriftReport(
    val streams: Set<AudioStream> = emptySet(),
    val ringerDrifted: Boolean = false,
) {
    val hasDrift: Boolean get() = streams.isNotEmpty() || ringerDrifted
}

interface ProfileAudioApplier {

    /**
     * Applies a profile's audio state.
     *
     * @param allowRamp whether the caller can afford a gradual transition. It is `false`
     *   in degraded (inexact alarm) mode, because there the app has no exemption to start
     *   a foreground service from the background and a ramp cannot outlive the receiver.
     *   See docs/02, risk N7.
     */
    suspend fun apply(profile: SoundProfile, allowRamp: Boolean): ApplyReport

    /** Puts back a captured baseline, in native steps, with the same ordering rules. */
    suspend fun restore(snapshot: AudioSnapshot): ApplyReport

    /**
     * Compares the device against a profile, in **native indices**.
     *
     * Never in percentages: on a 7-step device 30 % is written as step 2 and reads back as
     * 29 %, so a percentage comparison would report drift on every single watchdog pass,
     * re-apply, and loop for ever. See the contract stated in `VolumeMath`.
     */
    fun drift(profile: SoundProfile): DriftReport
}

@Singleton
class ProfileAudioApplierImpl @Inject constructor(
    private val volumeController: VolumeController,
    private val ringerController: RingerController,
    private val capabilities: AudioCapabilities,
    private val callStateProbe: CallStateProbe,
    private val mediaActivityProbe: MediaActivityProbe,
) : ProfileAudioApplier {

    override suspend fun apply(profile: SoundProfile, allowRamp: Boolean): ApplyReport {
        val normalized = profile.normalized()

        if (normalized.options.skipDuringCall && callStateProbe.isCallActive()) {
            // Changing the ring volume while the phone is ringing, or muting the earpiece
            // mid-conversation, is the worst thing this app could do.
            return ApplyReport(ApplyOutcome.SKIPPED, skipReason = LogReason.SKIPPED_IN_CALL)
        }

        val requested = normalized.volumes.requested()
        if (requested.isEmpty() && normalized.ringerMode == null) {
            return ApplyReport(ApplyOutcome.NO_CHANGE)
        }

        val targets: Map<AudioStream, Int> = requested.associate { (stream, percent) ->
            stream to VolumeMath.percentToIndex(
                percent = percent,
                minSteps = capabilities.minIndex(stream),
                maxSteps = capabilities.maxIndex(stream),
            )
        }

        val rampSeconds = if (allowRamp) normalized.options.transitionSeconds else 0
        return runOrdered(
            targets = targets,
            ringerTarget = normalized.ringerMode,
            rampSeconds = rampSeconds,
            protectPlayingMedia = normalized.options.skipIfMediaPlaying,
        )
    }

    override suspend fun restore(snapshot: AudioSnapshot): ApplyReport {
        val targets: Map<AudioStream, Int> = snapshot.levels
            .filterKeys { it.writable }
            .mapValues { (_, level) -> level.steps }

        return runOrdered(
            targets = targets,
            ringerTarget = snapshot.ringerMode,
            // A restore is a correction, not an effect. Fading back to the morning state
            // would only widen the window in which the device is in neither state.
            rampSeconds = 0,
            // Restoring is the user getting their own settings back; media playback is not
            // a reason to withhold them.
            protectPlayingMedia = false,
        )
    }

    override fun drift(profile: SoundProfile): DriftReport {
        // On a fixed-volume device nothing can ever match, and reporting drift would make
        // the watchdog re-apply for ever and fill the activity log with noise.
        if (capabilities.isVolumeFixed()) return DriftReport()

        val normalized = profile.normalized()
        val protectMedia = normalized.options.skipIfMediaPlaying && mediaActivityProbe.isMediaPlaying()

        val drifted = normalized.volumes.requested()
            .filter { (stream, _) -> !(stream == AudioStream.MUSIC && protectMedia) }
            .filter { (stream, percent) ->
                val target = VolumeMath.percentToIndex(
                    percent = percent,
                    minSteps = capabilities.minIndex(stream),
                    maxSteps = capabilities.maxIndex(stream),
                )
                capabilities.currentIndex(stream) != target
            }
            .map { it.first }
            .toSet()

        val ringerDrifted = normalized.ringerMode != null &&
            ringerController.current() != normalized.ringerMode

        return DriftReport(streams = drifted, ringerDrifted = ringerDrifted)
    }

    /**
     * The deterministic apply order, which is not a style choice but a correctness
     * requirement.
     *
     * `setRingerMode` rewrites the ring volume, and writing a ring-aliased stream to 0
     * flips the ringer mode. Applying the same profile in two different orders therefore
     * produces two different devices. The rule:
     *
     *  * target `NORMAL` → **ringer first**, then volumes, so the volumes we write are the
     *    ones that survive (a later `setRingerMode(NORMAL)` would restore the system's
     *    remembered ring level and undo us).
     *  * target `SILENT`/`VIBRATE` → **non-aliased streams first** (MUSIC, ALARM,
     *    VOICE_CALL), then the aliased ones, then `setRingerMode` **last**, so that the
     *    silencing is the final word and nothing can bounce the device back to NORMAL.
     *  * no ringer target → non-aliased first anyway, so an aliased write that flips the
     *    mode as a side effect happens at a single, predictable point.
     */
    private suspend fun runOrdered(
        targets: Map<AudioStream, Int>,
        ringerTarget: RingerMode?,
        rampSeconds: Int,
        protectPlayingMedia: Boolean,
    ): ApplyReport {
        val ringerFirst = ringerTarget == RingerMode.NORMAL
        var ringerResult: AudioOpResult? = null

        if (ringerFirst && ringerTarget != null) {
            ringerResult = ringerController.set(ringerTarget)
        }

        val ordered = AudioStream.WRITABLE
            .filter { it in targets }
            .sortedBy { if (it in AudioStream.RING_ALIASED) 1 else 0 }

        val results = writeVolumes(ordered, targets, rampSeconds, protectPlayingMedia)

        if (!ringerFirst && ringerTarget != null) {
            ringerResult = ringerController.set(ringerTarget)
        }

        return ApplyReport(
            outcome = outcomeOf(results, ringerResult),
            streams = results,
            ringer = ringerResult,
        )
    }

    private suspend fun writeVolumes(
        ordered: List<AudioStream>,
        targets: Map<AudioStream, Int>,
        rampSeconds: Int,
        protectPlayingMedia: Boolean,
    ): Map<AudioStream, AudioOpResult> {
        val results = LinkedHashMap<AudioStream, AudioOpResult>(ordered.size)

        // Evaluated once, not per stream: a probe per write would make the decision depend
        // on when a track happened to end mid-transition.
        val mediaPlaying = protectPlayingMedia && mediaActivityProbe.isMediaPlaying()

        // Only *lowering* is withheld: raising the volume of something the user is already
        // listening to is exactly what they asked for.
        val (withheld, writable) = ordered.partition { stream ->
            mediaPlaying &&
                stream == AudioStream.MUSIC &&
                targets.getValue(stream) < capabilities.currentIndex(stream)
        }
        withheld.forEach { stream ->
            results[stream] = AudioOpResult.Refused(RefusalReason.MEDIA_PLAYING)
        }

        if (rampSeconds <= 0) {
            writable.forEach { stream ->
                results[stream] = volumeController.setIndex(stream, targets.getValue(stream))
            }
            return results
        }

        // Ramping two aliased streams at once would have them fight over the same
        // underlying value, so only the dominant one is faded; the rest get a single write
        // once the fade is over, which is the only outcome that is well defined on a
        // coupled device anyway.
        val dominantAliased = writable.firstOrNull { it in AudioStream.RING_ALIASED }
        val ramped = writable.filter { it !in AudioStream.RING_ALIASED || it == dominantAliased }
        val afterwards = writable - ramped.toSet()

        val ramps = coroutineScope {
            ramped.map { stream ->
                async {
                    stream to volumeController
                        .ramp(stream, targets.getValue(stream), rampSeconds)
                        .lastOrNull()
                }
            }.awaitAll()
        }
        ramps.forEach { (stream, result) -> if (result != null) results[stream] = result }

        afterwards.forEach { stream ->
            results[stream] = volumeController.setIndex(stream, targets.getValue(stream))
        }
        return results
    }

    private fun outcomeOf(
        results: Map<AudioStream, AudioOpResult>,
        ringerResult: AudioOpResult?,
    ): ApplyOutcome {
        val all = results.values + listOfNotNull(ringerResult)
        if (all.isEmpty()) return ApplyOutcome.NO_CHANGE
        val succeeded = all.count { it.isSuccess }
        return when (succeeded) {
            all.size -> ApplyOutcome.APPLIED
            0 -> ApplyOutcome.REFUSED
            else -> ApplyOutcome.PARTIAL
        }
    }
}
