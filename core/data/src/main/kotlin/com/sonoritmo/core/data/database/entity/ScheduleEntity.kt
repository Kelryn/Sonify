package com.sonoritmo.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recurring window during which a profile should be in force.
 *
 * ## Why the foreign key points at `profiles.uuid` and not at `profiles.id`
 *
 * The domain type [com.sonoritmo.core.domain.model.Schedule] identifies its parent by
 * uuid, because that is the identity that survives export and import. A rowid-based key
 * would force a lookup on every write and a join on every read purely to translate
 * between the two, and every one of those translations is a chance to attach a window to
 * the wrong profile. `uuid` carries a unique index, so SQLite accepts it as a parent key
 * and the cascade behaves identically.
 *
 * `ON DELETE CASCADE` is the only cascade in the schema, and it is right here: a window
 * without a profile means nothing at all, so there is nothing worth keeping. Contrast
 * with `activity_log`, which uses `SET NULL` because an audit record is worth keeping
 * even when its subject is gone.
 *
 * ## Why start + duration
 *
 * `start_minute` + `duration_minutes` instead of start/end: crossing midnight stops
 * being a special case (it is a duration that overflows), 24 h becomes expressible, and
 * the duration used to break priority ties is a stored field rather than a computation
 * that has to agree between Kotlin and SQL (docs/02, amendment E-01).
 *
 * `days_mask` is a bitmask, bit `n` = ISO day `n + 1`, valid range 1..127. Text like
 * `"MON,TUE"` would not be indexable, would be locale-sensitive and would break on an
 * enum rename (amendment E-02). The range is enforced by
 * [com.sonoritmo.core.domain.model.Schedule.validate]; Room cannot declare a `CHECK`.
 */
@Entity(
    tableName = "schedules",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["profile_uuid"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        // Required by the foreign key: without it every cascade is a full scan, and
        // Room warns at compile time.
        Index(value = ["profile_uuid"]),
        // The hot path: "which windows can be running today", filtered in SQL rather
        // than by pulling every row into memory on each watchdog pass.
        Index(value = ["enabled", "days_mask"]),
        // Same question without the enabled filter, for the timeline screen (RF-18).
        Index(value = ["days_mask"]),
    ],
)
data class ScheduleEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "uuid")
    val uuid: String,

    @ColumnInfo(name = "profile_uuid")
    val profileUuid: String,

    @ColumnInfo(name = "enabled", defaultValue = "1")
    val enabled: Boolean = true,

    /** 0..1439, local wall-clock minute the window opens on. */
    @ColumnInfo(name = "start_minute")
    val startMinute: Int,

    /** 1..1440. 1440 is a full day; 0 is forbidden — it would be a window of no length. */
    @ColumnInfo(name = "duration_minutes")
    val durationMinutes: Int,

    /** 1..127. Bit 0 = Monday (ISO-8601). */
    @ColumnInfo(name = "days_mask")
    val daysMask: Int,

    /** Optional user label, so several windows on one profile stay distinguishable. */
    @ColumnInfo(name = "label")
    val label: String? = null,
)
