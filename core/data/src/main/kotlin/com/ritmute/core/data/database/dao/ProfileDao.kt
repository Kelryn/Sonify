package com.ritmute.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ritmute.core.data.database.entity.ProfileEntity
import com.ritmute.core.data.database.entity.ProfileWithSchedules
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ProfileDao {

    // ── Reads ────────────────────────────────────────────────────────────────
    //
    // Ordering is always (sort_order, created_at): the user controls the order
    // (RF-08) and ties fall back to age, never to the rowid — the rowid must not leak
    // into anything the user can observe.

    @Query("SELECT * FROM profiles ORDER BY sort_order ASC, created_at ASC")
    abstract fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE enabled = 1 ORDER BY sort_order ASC, created_at ASC")
    abstract fun observeEnabled(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE uuid = :uuid")
    abstract fun observeByUuid(uuid: String): Flow<ProfileEntity?>

    /**
     * `@Transaction` is not optional on a relation query: without it Room runs the
     * parent and child queries separately, and a concurrent write between the two
     * returns a profile with somebody else's windows.
     */
    @Transaction
    @Query("SELECT * FROM profiles ORDER BY sort_order ASC, created_at ASC")
    abstract fun observeAllWithSchedules(): Flow<List<ProfileWithSchedules>>

    @Query("SELECT * FROM profiles ORDER BY sort_order ASC, created_at ASC")
    abstract suspend fun getAll(): List<ProfileEntity>

    @Query("SELECT * FROM profiles WHERE uuid = :uuid")
    abstract suspend fun getByUuid(uuid: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id")
    abstract suspend fun getById(id: Long): ProfileEntity?

    @Query("SELECT id FROM profiles WHERE uuid = :uuid")
    abstract suspend fun getIdByUuid(uuid: String): Long?

    @Query("SELECT COUNT(*) FROM profiles")
    abstract suspend fun count(): Int

    /** Read before deleting, so the orphaned system zen rule can be removed afterwards. */
    @Query("SELECT zen_rule_id FROM profiles WHERE uuid = :uuid")
    abstract suspend fun getZenRuleId(uuid: String): String?

    @Query("SELECT MAX(sort_order) FROM profiles")
    abstract suspend fun maxSortOrder(): Int?

    // ── Writes ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(profile: ProfileEntity): Long

    @Update
    abstract suspend fun update(profile: ProfileEntity)

    /**
     * Insert or update, keyed on the **uuid**.
     *
     * Deliberately not `@Upsert`: that resolves conflicts against the primary key, so a
     * profile arriving from an import with `id = 0` and an already-present uuid would
     * fail the unique index and then "update" row 0, which does not exist — a silent
     * no-op. Resolving the id here first makes the intent explicit and keeps the
     * generated rowid stable across imports.
     */
    @Transaction
    open suspend fun upsertByUuid(profile: ProfileEntity): Long {
        val existingId = getIdByUuid(profile.uuid)
        return if (existingId == null) {
            insert(profile.copy(id = 0L))
        } else {
            update(profile.copy(id = existingId))
            existingId
        }
    }

    @Query("UPDATE profiles SET enabled = :enabled, updated_at = :updatedAtUtc WHERE uuid = :uuid")
    abstract suspend fun setEnabled(uuid: String, enabled: Boolean, updatedAtUtc: Long)

    @Query("UPDATE profiles SET zen_rule_id = :zenRuleId WHERE uuid = :uuid")
    abstract suspend fun setZenRuleId(uuid: String, zenRuleId: String?)

    @Query("UPDATE profiles SET sort_order = :sortOrder WHERE uuid = :uuid")
    abstract suspend fun setSortOrder(uuid: String, sortOrder: Int)

    /** Whole reorder in one transaction: a half-applied order is visibly wrong. */
    @Transaction
    open suspend fun reorder(orderedUuids: List<String>) {
        orderedUuids.forEachIndexed { index, uuid -> setSortOrder(uuid, index) }
    }

    /** Windows cascade; activity log rows survive with their foreign keys set to null. */
    @Query("DELETE FROM profiles WHERE uuid = :uuid")
    abstract suspend fun deleteByUuid(uuid: String): Int

    @Query("DELETE FROM profiles")
    abstract suspend fun deleteAll()
}
