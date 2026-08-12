package com.sonoritmo.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sonoritmo.core.domain.model.RingerMode
import java.time.Instant

/**
 * The audio state of the device before automation touched it, so it can be put back.
 *
 * ## One baseline, not a stack
 *
 * There is exactly one live row, and it always has `id = 0`. A snapshot is captured when
 * automation goes from "nothing active" to "something active", and consumed when it goes
 * back; profile-to-profile transitions capture nothing.
 *
 * A stack of nested snapshots corrupts itself the moment the process is killed
 * mid-transition, which on battery-aggressive OEMs is the normal case, and there is no
 * way to tell a corrupt stack from a correct one afterwards. With a single baseline, a
 * cold start with a baseline present and no active window is trivially recoverable:
 * restore it and delete it (docs/02, amendment E-07).
 *
 * Room cannot declare a `CHECK (id = 0)`, so the invariant is held by the DAO: the only
 * insert fixes `id` to 0 and the only read is `WHERE id = 0`. That is simpler and more
 * verifiable than a partial index Room could neither declare nor validate.
 *
 * ## Native steps, not percentages
 *
 * [levelsJson] holds `steps`/`minSteps`/`maxSteps` exactly as the device reported them.
 * A profile's *configuration* is in percent so it stays portable between a 7-step and a
 * 30-step device; a *restoration* has to give back precisely what was there, and the
 * percentage round trip is not bit-exact once `getStreamMinVolume` is in play
 * (docs/02, amendment E-06).
 */
@Entity(
    tableName = "audio_snapshots",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["owner_profile_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["owner_profile_uuid"])],
)
data class AudioSnapshotEntity(
    /** Always [BASELINE_ID]. See the class documentation. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long = BASELINE_ID,

    @ColumnInfo(name = "captured_at_utc")
    val capturedAt: Instant,

    /**
     * Which profile's activation caused the capture. Nulled if that profile is deleted;
     * the snapshot itself stays valid, since it describes the device, not the profile.
     */
    @ColumnInfo(name = "owner_profile_uuid")
    val ownerProfileUuid: String? = null,

    /**
     * `{"RING":{"steps":3,"minSteps":0,"maxSteps":7}, …}`.
     *
     * The one place in the schema where structured data lives in a single column, and it
     * earns the exception: these numbers are never queried, filtered or sorted — they are
     * read whole, once, to restore. Keeping them as one document means that the day the
     * platform adds a stream it is a change to a serializable class with
     * `ignoreUnknownKeys`, not a schema migration across 21 columns.
     */
    @ColumnInfo(name = "levels_json")
    val levelsJson: String,

    @ColumnInfo(name = "ringer_mode")
    val ringerMode: RingerMode,

    /**
     * `NotificationManager.INTERRUPTION_FILTER_*` at capture time.
     *
     * Diagnostic only, never restored: from Android 15 the global filter is readable but
     * not writable by an app, so pretending we can put it back would be a lie
     * (docs/02, decision D-C1).
     */
    @ColumnInfo(name = "interruption_filter")
    val interruptionFilter: Int,

    /**
     * `Build.MODEL` plus the per-stream volume scales at capture time.
     *
     * Guards the one case that would be actively harmful: an Android Backup restored
     * onto a different phone. If the scales do not match, applying 25 steps to a 7-step
     * device is not a restoration, it is damage — so a snapshot whose fingerprint has
     * changed is discarded and the fact is logged instead.
     */
    @ColumnInfo(name = "device_fingerprint")
    val deviceFingerprint: String,
) {
    companion object {
        /** The single baseline row. */
        const val BASELINE_ID: Long = 0L
    }
}
