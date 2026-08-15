package com.ritmute.core.system.audio

import android.media.AudioManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Is something actually playing?", used by `skipIfMediaPlaying` so a scheduled profile
 * never mutes the podcast the user is listening to (RF-25).
 *
 * `isMusicActive()` is true for any active playback on `STREAM_MUSIC`, whatever app owns
 * it, and needs no permission or media-session bookkeeping.
 */
interface MediaActivityProbe {
    fun isMediaPlaying(): Boolean
}

@Singleton
class MediaActivityProbeImpl @Inject constructor(
    private val audioManager: AudioManager,
) : MediaActivityProbe {

    override fun isMediaPlaying(): Boolean = audioManager.isMusicActive
}
