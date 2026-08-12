package com.sonoritmo.core.system.audio

import android.app.NotificationManager
import com.sonoritmo.core.domain.model.AudioSnapshot
import com.sonoritmo.core.domain.model.AudioStream
import com.sonoritmo.core.domain.model.StreamLevel
import com.sonoritmo.core.domain.port.TimeSource
import javax.inject.Inject
import javax.inject.Singleton

interface AudioStateSnapshotter {

    /**
     * Captures the whole audible state of the device, in **native steps**.
     *
     * Percentages are for the user's portable configuration; steps are for faithful
     * restoration. Converting to a percentage and back loses information on devices with
     * few steps, and a restore that is one step off is a bug the user will notice at 07:00.
     * See docs/02, amendment E-06.
     */
    fun capture(ownerProfileUuid: String?): AudioSnapshot
}

@Singleton
class AudioStateSnapshotterImpl @Inject constructor(
    private val capabilities: AudioCapabilities,
    private val ringerController: RingerController,
    private val notificationManager: NotificationManager,
    private val timeSource: TimeSource,
) : AudioStateSnapshotter {

    override fun capture(ownerProfileUuid: String?): AudioSnapshot {
        // Every stream, including ACCESSIBILITY: it is read-only for us, but the history
        // screen shows it and a user comparing "before/after" would find a hole otherwise.
        val levels: Map<AudioStream, StreamLevel> = AudioStream.entries.associateWith { stream ->
            capabilities.levelOf(stream)
        }

        return AudioSnapshot(
            capturedAt = timeSource.now(),
            levels = levels,
            ringerMode = ringerController.current(),
            interruptionFilter = readInterruptionFilter(),
            ownerProfileUuid = ownerProfileUuid,
        )
    }

    /**
     * Diagnostic only — this value is never written back.
     *
     * From Android 15 the global interruption filter is readable but not writable by apps:
     * a write becomes an implicit zen rule and the effective policy is the most restrictive
     * of all active rules. Storing it as "restorable" would be a promise the platform does
     * not let us keep. See docs/02, decision D-C1.
     */
    private fun readInterruptionFilter(): Int = try {
        notificationManager.currentInterruptionFilter
    } catch (security: SecurityException) {
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }
}
