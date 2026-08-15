package com.ritmute.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ritmute.core.data.database.entity.AudioSnapshotEntity
import kotlinx.coroutines.flow.Flow

/**
 * The single audio baseline.
 *
 * Every statement pins `id = 0`. That, and only that, is what enforces "one baseline,
 * not a stack" — Room cannot declare the `CHECK` and a partial unique index would be
 * neither declarable nor validated by its schema checker.
 */
@Dao
abstract class SnapshotDao {

    @Query("SELECT * FROM audio_snapshots WHERE id = 0")
    abstract fun observeBaseline(): Flow<AudioSnapshotEntity?>

    @Query("SELECT * FROM audio_snapshots WHERE id = 0")
    abstract suspend fun getBaseline(): AudioSnapshotEntity?

    /**
     * Capture only if there is nothing to restore yet.
     *
     * `IGNORE` rather than `REPLACE` is the whole point: a baseline is taken on the
     * "nothing active → something active" edge, and a second capture during a
     * profile-to-profile transition would overwrite the user's real settings with the
     * previous profile's, permanently. Returns `true` when the row was actually written.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(snapshot: AudioSnapshotEntity): Long

    suspend fun captureIfAbsent(snapshot: AudioSnapshotEntity): Boolean =
        insertIfAbsent(snapshot.copy(id = AudioSnapshotEntity.BASELINE_ID)) != -1L

    @Query("DELETE FROM audio_snapshots WHERE id = 0")
    abstract suspend fun clearBaseline(): Int

    /**
     * Read and delete in one transaction.
     *
     * If the process dies between restoring the audio and clearing the baseline, the
     * next start would restore again — over settings the user may have changed by hand
     * in the meantime, silently undoing their change. Consuming atomically removes that
     * window entirely.
     */
    @Transaction
    open suspend fun consumeBaseline(): AudioSnapshotEntity? {
        val baseline = getBaseline()
        if (baseline != null) clearBaseline()
        return baseline
    }

    /**
     * Consume only if the snapshot was taken on this device.
     *
     * Guards the Android Backup case: a database restored onto another phone brings a
     * baseline whose volume scales belong to a different device, and applying it is not
     * a restoration but damage. A stale-fingerprint baseline is dropped, and the caller
     * gets `null` so it can log the fact rather than act on it.
     */
    @Transaction
    open suspend fun consumeBaselineForDevice(deviceFingerprint: String): AudioSnapshotEntity? {
        val baseline = consumeBaseline() ?: return null
        return baseline.takeIf { it.deviceFingerprint == deviceFingerprint }
    }
}
