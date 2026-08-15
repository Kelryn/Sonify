package com.ritmute.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ritmute.core.data.database.entity.AutomationStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * The single automation-state row.
 *
 * Writes are column-scoped `UPDATE`s rather than whole-row replacements. That is not a
 * micro-optimisation: this row is written from the alarm receiver, the watchdog, the
 * quick-settings tile and the UI, and a read-modify-write of the whole row would let two
 * of them clobber each other's field — the global pause disappearing because the
 * watchdog wrote back a row it had read before the pause was set.
 *
 * Every write is preceded by [ensureRow] inside the same transaction, so callers never
 * have to think about whether the row exists yet.
 */
@Dao
abstract class AutomationStateDao {

    @Query("SELECT * FROM automation_state WHERE id = 0")
    abstract fun observe(): Flow<AutomationStateEntity?>

    @Query("SELECT * FROM automation_state WHERE id = 0")
    abstract suspend fun get(): AutomationStateEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertIfAbsent(state: AutomationStateEntity): Long

    suspend fun ensureRow() {
        insertIfAbsent(AutomationStateEntity())
    }

    @Query("UPDATE automation_state SET global_pause_until_utc = :untilUtc WHERE id = 0")
    abstract suspend fun updateGlobalPause(untilUtc: Long?)

    @Query(
        """
        UPDATE automation_state
        SET manual_profile_uuid = :profileUuid,
            manual_until_utc = :untilUtc,
            manual_activated_at_utc = :activatedAtUtc
        WHERE id = 0
        """,
    )
    abstract suspend fun updateManualOverride(
        profileUuid: String?,
        untilUtc: Long?,
        activatedAtUtc: Long?,
    )

    @Query(
        """
        UPDATE automation_state
        SET applied_profile_uuid = :profileUuid,
            applied_schedule_uuid = :scheduleUuid,
            applied_at_utc = :appliedAtUtc
        WHERE id = 0
        """,
    )
    abstract suspend fun updateApplied(
        profileUuid: String?,
        scheduleUuid: String?,
        appliedAtUtc: Long?,
    )

    @Query("UPDATE automation_state SET next_transition_at_utc = :atUtc WHERE id = 0")
    abstract suspend fun updateNextTransition(atUtc: Long?)

    @Query("UPDATE automation_state SET last_reconciliation_at_utc = :atUtc WHERE id = 0")
    abstract suspend fun updateLastReconciliation(atUtc: Long?)

    @Query("UPDATE automation_state SET repair_count = repair_count + 1 WHERE id = 0")
    abstract suspend fun incrementRepairCount()

    // ── Transactional wrappers ───────────────────────────────────────────────

    @Transaction
    open suspend fun setGlobalPause(untilUtc: Long?) {
        ensureRow()
        updateGlobalPause(untilUtc)
    }

    @Transaction
    open suspend fun setManualOverride(
        profileUuid: String?,
        untilUtc: Long?,
        activatedAtUtc: Long?,
    ) {
        ensureRow()
        updateManualOverride(profileUuid, untilUtc, activatedAtUtc)
    }

    @Transaction
    open suspend fun setApplied(profileUuid: String?, scheduleUuid: String?, appliedAtUtc: Long?) {
        ensureRow()
        updateApplied(profileUuid, scheduleUuid, appliedAtUtc)
    }

    @Transaction
    open suspend fun setNextTransition(atUtc: Long?) {
        ensureRow()
        updateNextTransition(atUtc)
    }

    /**
     * One statement pair for the end of a reconciliation pass.
     *
     * `repaired` is what feeds the health KPI on the diagnostics screen: a watchdog that
     * keeps finding the state wrong is the signal that the OEM is killing the app, which
     * is precisely what the user needs told (risk N3).
     */
    @Transaction
    open suspend fun recordReconciliation(atUtc: Long, repaired: Boolean) {
        ensureRow()
        updateLastReconciliation(atUtc)
        if (repaired) incrementRepairCount()
    }

    @Query("DELETE FROM automation_state")
    abstract suspend fun clear()
}
