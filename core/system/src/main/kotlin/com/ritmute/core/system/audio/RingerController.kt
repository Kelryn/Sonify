package com.ritmute.core.system.audio

import android.media.AudioManager
import com.ritmute.core.domain.model.RingerMode
import javax.inject.Inject
import javax.inject.Singleton

interface RingerController {

    /** The device's current ringer mode, normalised into the domain vocabulary. */
    fun current(): RingerMode

    /**
     * Sets the ringer mode, verifying by reading back.
     *
     * Read-back matters more here than anywhere else: several OEM layers accept
     * `setRingerMode` and revert it milliseconds later, and Do Not Disturb can hold the
     * device in silent regardless of what we asked for.
     */
    fun set(mode: RingerMode): AudioOpResult
}

@Singleton
class RingerControllerImpl @Inject constructor(
    private val audioManager: AudioManager,
    private val capabilities: AudioCapabilities,
) : RingerController {

    override fun current(): RingerMode = fromPlatform(audioManager.ringerMode)

    override fun set(mode: RingerMode): AudioOpResult {
        val currentMode = current()
        if (currentMode == mode) return AudioOpResult.Applied()

        // AOSP throws SecurityException when a transition into *or out of* silent is
        // attempted without notification policy access. Checking first turns a crash into
        // a typed refusal the diagnostics screen can act on; the catch below stays as a
        // net, because the access can be revoked between the check and the call.
        val touchesSilent = mode == RingerMode.SILENT || currentMode == RingerMode.SILENT
        if (touchesSilent && !capabilities.isNotificationPolicyAccessGranted()) {
            return AudioOpResult.Refused(RefusalReason.NO_NOTIFICATION_POLICY_ACCESS)
        }

        try {
            audioManager.ringerMode = toPlatform(mode)
        } catch (security: SecurityException) {
            return AudioOpResult.Refused(RefusalReason.SECURITY_EXCEPTION)
        }

        return if (current() == mode) {
            AudioOpResult.Applied()
        } else {
            AudioOpResult.SilentlyIgnored()
        }
    }

    private fun toPlatform(mode: RingerMode): Int = when (mode) {
        RingerMode.NORMAL -> AudioManager.RINGER_MODE_NORMAL
        RingerMode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
        RingerMode.SILENT -> AudioManager.RINGER_MODE_SILENT
    }

    private fun fromPlatform(mode: Int): RingerMode = when (mode) {
        AudioManager.RINGER_MODE_SILENT -> RingerMode.SILENT
        AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
        // RINGER_MODE_NORMAL, and any value a future platform adds. Treating an unknown
        // mode as NORMAL is the safe default: it never silences a phone by accident.
        else -> RingerMode.NORMAL
    }
}
