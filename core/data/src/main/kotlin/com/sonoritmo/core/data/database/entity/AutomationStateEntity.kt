package com.sonoritmo.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * The single row (`id = 0`) holding everything about automation that is neither a
 * profile nor a schedule and that must survive process death and reboot.
 *
 * ## Why this is in the database and not in DataStore
 *
 * [manualProfileUuid] and [appliedProfileUuid] reference profiles. When the user deletes
 * the profile they had activated by hand, those references have to be cleared **inside
 * the same transaction as the delete** — a key-value store has no way of even knowing
 * the delete happened, so it would keep a dangling reference and the next reconciliation
 * would try to apply a profile that no longer exists.
 *
 * The general rule this establishes for the project: anything that takes part in
 * conflict resolution lives in the database; DataStore is for UI preferences only,
 * because the two do not share a transaction (docs/02, amendment E-08).
 *
 * The row is guaranteed to exist by the DAO, which inserts the all-null default with
 * `OnConflictStrategy.IGNORE` before every write. Room cannot express `CHECK (id = 0)`.
 */
@Entity(
    tableName = "automation_state",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["manual_profile_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["applied_profile_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["applied_schedule_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["manual_profile_uuid"]),
        Index(value = ["applied_profile_uuid"]),
        Index(value = ["applied_schedule_uuid"]),
    ],
)
data class AutomationStateEntity(
    /** Always [SINGLETON_ID]. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Long = SINGLETON_ID,

    /** All rules suspended until this instant. Null = not paused (RF-17). */
    @ColumnInfo(name = "global_pause_until_utc")
    val globalPauseUntil: Instant? = null,

    /** Profile the user activated by hand. Beats anything scheduled (RF-06). */
    @ColumnInfo(name = "manual_profile_uuid")
    val manualProfileUuid: String? = null,

    /** When the manual activation expires. Null with a profile set = indefinite. */
    @ColumnInfo(name = "manual_until_utc")
    val manualUntil: Instant? = null,

    @ColumnInfo(name = "manual_activated_at_utc")
    val manualActivatedAt: Instant? = null,

    /** What is actually applied right now, so drift can be detected and repaired. */
    @ColumnInfo(name = "applied_profile_uuid")
    val appliedProfileUuid: String? = null,

    @ColumnInfo(name = "applied_schedule_uuid")
    val appliedScheduleUuid: String? = null,

    @ColumnInfo(name = "applied_at_utc")
    val appliedAt: Instant? = null,

    /** Shown on the diagnostics screen (RF-33) and used to verify the single alarm. */
    @ColumnInfo(name = "next_transition_at_utc")
    val nextTransitionAt: Instant? = null,

    @ColumnInfo(name = "last_reconciliation_at_utc")
    val lastReconciliationAt: Instant? = null,

    /** How many times the watchdog found the state wrong and fixed it. Health KPI. */
    @ColumnInfo(name = "repair_count", defaultValue = "0")
    val repairCount: Int = 0,
) {
    companion object {
        const val SINGLETON_ID: Long = 0L
    }
}
