package com.ritmute.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ritmute.core.data.database.entity.ActivityLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * The activity log.
 *
 * Every read orders by `id DESC` and never by `timestamp_utc DESC`. See
 * [ActivityLogEntity] for why: the user can move the clock backwards, the rowid cannot.
 */
@Dao
interface ActivityLogDao {

    // ── Reads ────────────────────────────────────────────────────────────────

    @Query("SELECT * FROM activity_log ORDER BY id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ActivityLogEntity>>

    /**
     * The filtered history (RF-46).
     *
     * Every filter is expressed as "parameter is neutral OR it matches", which keeps one
     * statement — and therefore one prepared statement and one query plan — instead of a
     * `@RawQuery` built by string concatenation. `typeCount` guards the `IN` clause:
     * SQLite accepts an empty `IN ()` list (it is false), but relying on that alone
     * would be a subtlety nobody reading this should have to know.
     */
    @Query(
        """
        SELECT * FROM activity_log
        WHERE (:typeCount = 0 OR type IN (:typeCodes))
          AND (:profileUuid IS NULL OR profile_uuid = :profileUuid)
          AND (:onlyFailures = 0 OR success = 0)
          AND (:sinceUtc IS NULL OR timestamp_utc >= :sinceUtc)
        ORDER BY id DESC
        LIMIT :limit
        """,
    )
    fun observeFiltered(
        typeCodes: List<String>,
        typeCount: Int,
        profileUuid: String?,
        onlyFailures: Boolean,
        sinceUtc: Long?,
        limit: Int,
    ): Flow<List<ActivityLogEntity>>

    @Query("SELECT * FROM activity_log ORDER BY id DESC LIMIT :limit OFFSET :offset")
    suspend fun getPage(limit: Int, offset: Int): List<ActivityLogEntity>

    @Query("SELECT COUNT(*) FROM activity_log")
    suspend fun count(): Int

    // ── Writes ───────────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: ActivityLogEntity): Long

    /** One statement for the several lines a single transition produces. */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entries: List<ActivityLogEntity>): List<Long>

    // ── Retention (RF-38) ────────────────────────────────────────────────────
    //
    // Three statements, all index-backed. Note what is *not* here: the
    // `WHERE id NOT IN (SELECT … LIMIT n)` idiom, which is quadratic and is the reason
    // history purges in this kind of app show up as jank.

    /** Ordinary rows past the age limit. */
    @Query(
        """
        DELETE FROM activity_log
        WHERE timestamp_utc < :cutoffUtc
          AND type NOT IN (:protectedTypeCodes)
        """,
    )
    suspend fun purgeOlderThan(cutoffUtc: Long, protectedTypeCodes: List<String>): Int

    /**
     * Errors and permission changes, kept far longer.
     *
     * They are the rows that explain a failure, and they are rare. Letting a quiet month
     * of routine entries evict them would gut the one screen that answers "why did this
     * not work" (CU-07).
     */
    @Query(
        """
        DELETE FROM activity_log
        WHERE timestamp_utc < :cutoffUtc
          AND type IN (:protectedTypeCodes)
        """,
    )
    suspend fun purgeProtectedOlderThan(cutoffUtc: Long, protectedTypeCodes: List<String>): Int

    /**
     * Trim ordinary rows down to the newest [keepCount].
     *
     * The sub-select finds the id of the (keepCount + 1)-th newest ordinary row and
     * deletes everything at or below it — one indexed lookup plus one ranged delete.
     * `COALESCE(…, -1)` makes it a no-op when there are fewer rows than the limit.
     */
    @Query(
        """
        DELETE FROM activity_log
        WHERE type NOT IN (:protectedTypeCodes)
          AND id <= COALESCE(
              (SELECT id FROM activity_log
               WHERE type NOT IN (:protectedTypeCodes)
               ORDER BY id DESC
               LIMIT 1 OFFSET :keepCount),
              -1)
        """,
    )
    suspend fun purgeBeyondCount(keepCount: Int, protectedTypeCodes: List<String>): Int

    @Query("DELETE FROM activity_log")
    suspend fun clearAll(): Int
}
