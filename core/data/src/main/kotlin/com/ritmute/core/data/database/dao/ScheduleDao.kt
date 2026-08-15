package com.ritmute.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.ritmute.core.data.database.entity.ScheduleEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class ScheduleDao {

    // ── Reads ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM schedules ORDER BY start_minute ASC, duration_minutes ASC")
    abstract fun observeAll(): Flow<List<ScheduleEntity>>

    @Query(
        """
        SELECT * FROM schedules
        WHERE profile_uuid = :profileUuid
        ORDER BY start_minute ASC, duration_minutes ASC
        """,
    )
    abstract fun observeByProfile(profileUuid: String): Flow<List<ScheduleEntity>>

    @Query("SELECT * FROM schedules")
    abstract suspend fun getAll(): List<ScheduleEntity>

    @Query("SELECT * FROM schedules WHERE uuid = :uuid")
    abstract suspend fun getByUuid(uuid: String): ScheduleEntity?

    @Query("SELECT id FROM schedules WHERE uuid = :uuid")
    abstract suspend fun getIdByUuid(uuid: String): Long?

    @Query("SELECT * FROM schedules WHERE profile_uuid = :profileUuid")
    abstract suspend fun getByProfile(profileUuid: String): List<ScheduleEntity>

    @Query("SELECT COUNT(*) FROM schedules")
    abstract suspend fun count(): Int

    /**
     * The candidate set for one ISO day, filtered **in SQL**.
     *
     * The bitmask exists precisely so this can be an indexed `AND` instead of loading
     * every window into memory on every watchdog pass. `:dayMask` is a single bit
     * (`1 shl (isoDay - 1)`); the join drops windows whose profile is disabled, which
     * the resolver would discard anyway.
     *
     * Note it returns windows that *start* on that day. A window crossing midnight is
     * still running the next morning, so callers that need coverage of an instant must
     * also ask for the previous day — the domain's window expansion does exactly that.
     */
    @Query(
        """
        SELECT s.* FROM schedules AS s
        INNER JOIN profiles AS p ON p.uuid = s.profile_uuid
        WHERE s.enabled = 1
          AND p.enabled = 1
          AND (s.days_mask & :dayMask) != 0
        ORDER BY s.start_minute ASC
        """,
    )
    abstract suspend fun candidatesForDayMask(dayMask: Int): List<ScheduleEntity>

    /** Same filter as [candidatesForDayMask], observed, for the timeline screen. */
    @Query(
        """
        SELECT s.* FROM schedules AS s
        INNER JOIN profiles AS p ON p.uuid = s.profile_uuid
        WHERE s.enabled = 1
          AND p.enabled = 1
          AND (s.days_mask & :dayMask) != 0
        ORDER BY s.start_minute ASC
        """,
    )
    abstract fun observeCandidatesForDayMask(dayMask: Int): Flow<List<ScheduleEntity>>

    // ── Writes ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(schedule: ScheduleEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertAll(schedules: List<ScheduleEntity>): List<Long>

    @Update
    abstract suspend fun update(schedule: ScheduleEntity)

    /** Keyed on the uuid, for the same reason as [ProfileDao.upsertByUuid]. */
    @Transaction
    open suspend fun upsertByUuid(schedule: ScheduleEntity): Long {
        val existingId = getIdByUuid(schedule.uuid)
        return if (existingId == null) {
            insert(schedule.copy(id = 0L))
        } else {
            update(schedule.copy(id = existingId))
            existingId
        }
    }

    @Query("UPDATE schedules SET enabled = :enabled WHERE uuid = :uuid")
    abstract suspend fun setEnabled(uuid: String, enabled: Boolean)

    @Query("DELETE FROM schedules WHERE uuid = :uuid")
    abstract suspend fun deleteByUuid(uuid: String): Int

    @Query("DELETE FROM schedules WHERE profile_uuid = :profileUuid")
    abstract suspend fun deleteByProfile(profileUuid: String): Int

    /**
     * Replace a profile's whole set of windows atomically.
     *
     * Editing a profile's schedule is a single user action, so it has to be a single
     * database action too: a half-applied edit would leave the phone enforcing a window
     * the user just deleted until the next write happened to fix it.
     */
    @Transaction
    open suspend fun replaceForProfile(profileUuid: String, schedules: List<ScheduleEntity>) {
        deleteByProfile(profileUuid)
        if (schedules.isNotEmpty()) {
            insertAll(schedules.map { it.copy(id = 0L, profileUuid = profileUuid) })
        }
    }

    @Query("DELETE FROM schedules")
    abstract suspend fun deleteAll()
}
