package com.sonoritmo.core.system.audio

import android.media.AudioManager
import android.os.Build
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is the user on a call right now?", answered **without any permission**.
 *
 * The obvious implementation — `TelephonyManager.getCallState()` — needs
 * `READ_PHONE_STATE`, a scary runtime permission for an app whose selling point is that
 * it asks for nothing. `AudioManager.getMode()` needs no permission at all *and* covers
 * more ground: WhatsApp, Meet and every other VoIP app put the device into
 * `MODE_IN_COMMUNICATION`, which `TelephonyManager` never reports.
 *
 * That is why the project ships with `POST_NOTIFICATIONS` as its only runtime permission.
 * See docs/02, amendment E-15.
 */
interface CallStateProbe {
    fun isCallActive(): Boolean
}

@Singleton
class CallStateProbeImpl @Inject constructor(
    private val audioManager: AudioManager,
) : CallStateProbe {

    override fun isCallActive(): Boolean = when (val mode = audioManager.mode) {
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
        // The phone ringing counts: applying a profile mid-ring is the most jarring
        // possible moment to change the ring volume.
        AudioManager.MODE_RINGTONE,
        -> true

        else -> isModernCallMode(mode)
    }

    /**
     * Call modes added after the app's `minSdk`. They are compile-time constants, so
     * referring to them is safe on old devices, but the version guard documents that a
     * device below the API level simply never reports them.
     */
    private fun isModernCallMode(mode: Int): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            mode == AudioManager.MODE_CALL_SCREENING ||
                mode == AudioManager.MODE_CALL_REDIRECT ||
                mode == AudioManager.MODE_COMMUNICATION_REDIRECT

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            mode == AudioManager.MODE_CALL_SCREENING

        else -> false
    }
}
