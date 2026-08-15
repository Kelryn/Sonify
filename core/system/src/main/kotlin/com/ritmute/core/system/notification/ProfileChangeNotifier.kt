package com.ritmute.core.system.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ritmute.core.system.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the user a profile changed, when they asked to be told.
 *
 * The whole point of this app is that nothing about it is visible: it changes the sound and
 * gets out of the way. So this is opt-in per profile through `ProfileOptions.notifyOnApply`,
 * and silent by construction — a low-importance channel with no sound and no vibration,
 * because a notification that beeps to announce it has just silenced your phone would be
 * absurd.
 *
 * Distinct from the foreground-service notification in `ApplyProfileService`, which the
 * platform requires while the write happens and which says nothing useful to a person.
 */
interface ProfileChangeNotifier {

    /** Posts the "now on <profile>" note. Does nothing if the user never asked for it. */
    fun notifyApplied(profileName: String)

    /** Withdraws it. Called when nothing is active, so the shade matches reality. */
    fun clear()
}

@Singleton
class ProfileChangeNotifierImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : ProfileChangeNotifier {

    private val manager = NotificationManagerCompat.from(context)

    override fun notifyApplied(profileName: String) {
        // POST_NOTIFICATIONS is a runtime permission from API 33. Denied is a normal state,
        // not an error: the profile still applies, and the only thing lost is the note.
        // Posting without the check throws SecurityException inside a broadcast receiver.
        //
        // Written inline rather than behind a helper because lint's dataflow only recognises
        // the guard when it sits in the same function as the call it protects.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return

        ensureChannel()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ritmute)
            .setContentTitle(context.getString(R.string.core_system_applied_title, profileName))
            .setContentText(context.getString(R.string.core_system_applied_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(false)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()

        // try/catch rather than runCatching: lint cannot follow the call into a lambda, and
        // the permission can also be revoked between the check above and this line.
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (denied: SecurityException) {
            // Nothing to do and nothing to report: the profile was applied either way.
        }
    }

    override fun clear() {
        try {
            manager.cancel(NOTIFICATION_ID)
        } catch (denied: SecurityException) {
            // Same: withdrawing a note that may never have been posted is best-effort.
        }
    }

    private fun ensureChannel() {
        val system = context.getSystemService(NotificationManager::class.java) ?: return
        if (system.getNotificationChannel(CHANNEL_ID) != null) return

        // IMPORTANCE_LOW: appears in the shade, never makes a sound and never intrudes.
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.core_system_channel_changes),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        system.createNotificationChannel(channel)
    }

    private companion object {
        const val CHANNEL_ID = "profile_changes"

        /** Fixed, so a new profile replaces the previous note instead of stacking. */
        const val NOTIFICATION_ID = 4201
    }
}
