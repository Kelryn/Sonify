package com.sonoritmo.core.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.sonoritmo.core.domain.model.LogReason
import com.sonoritmo.core.domain.model.LogType
import java.time.Instant

/**
 * One immutable line of the "why did my phone sound like that?" record (D6, CU-07).
 *
 * ## The history outlives the profiles it talks about
 *
 * Both foreign keys are `ON DELETE SET NULL`, never `CASCADE`. An audit log is a record
 * of what happened; deleting a profile cannot be allowed to rewrite the past. The price
 * of `SET NULL` is that the row would become mute — "something silenced your phone" —
 * so [profileName] is denormalised on purpose. It is the only denormalisation in the
 * schema and it is what makes each row readable on its own (docs/02, amendment E-10).
 *
 * ## Ordering is by `id`, never by timestamp
 *
 * The user is allowed to move the clock backwards, and the app explicitly handles that
 * event. When they do, newer rows get a *smaller* `timestamp_utc` and any
 * `ORDER BY timestamp_utc` scrambles the history exactly when the user has most reason
 * to look at it. `id` comes from `AUTOINCREMENT` and is monotonic no matter what the
 * clock does, so it is the canonical order; the clock change itself is recorded as a
 * [LogReason.TIME_CHANGED] row so the jump is explainable rather than mysterious.
 */
@Entity(
    tableName = "activity_log",
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["profile_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
        ForeignKey(
            entity = ScheduleEntity::class,
            parentColumns = ["uuid"],
            childColumns = ["schedule_uuid"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["timestamp_utc"]),
        // RF-46: the history is filterable by type, newest first.
        Index(value = ["type", "id"]),
        // Both required by the foreign keys above.
        Index(value = ["profile_uuid"]),
        Index(value = ["schedule_uuid"]),
    ],
)
data class ActivityLogEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    @ColumnInfo(name = "timestamp_utc")
    val timestamp: Instant,

    /**
     * Zone and offset in force at the moment of the event, e.g. `Europe/Madrid`.
     *
     * CU-07 is literally "why did the sound change *at 3 in the morning*". After a trip
     * or a clock change, an instant alone cannot reconstruct the local time the user
     * actually saw, and that local time is the whole question.
     */
    @ColumnInfo(name = "zone_id")
    val zoneId: String,

    @ColumnInfo(name = "utc_offset_seconds")
    val utcOffsetSeconds: Int,

    @ColumnInfo(name = "type")
    val type: LogType,

    /**
     * Why it happened, as a code rather than a sentence.
     *
     * Free text would mean strings written in code: untranslatable, and impossible to
     * filter reliably. The UI composes the sentence from the code plus [paramsJson]
     * (docs/02, amendment E-09).
     */
    @ColumnInfo(name = "reason")
    val reason: LogReason,

    /** Structured arguments for the localised message, e.g. `{"stream":"RING","to":0}`. */
    @ColumnInfo(name = "params_json")
    val paramsJson: String? = null,

    @ColumnInfo(name = "profile_uuid")
    val profileUuid: String? = null,

    /** Denormalised so the row stays legible after the profile is deleted. */
    @ColumnInfo(name = "profile_name")
    val profileName: String? = null,

    @ColumnInfo(name = "schedule_uuid")
    val scheduleUuid: String? = null,

    @ColumnInfo(name = "success", defaultValue = "1")
    val success: Boolean = true,

    /** Technical diagnostics only (exception class, stream name). Never the headline. */
    @ColumnInfo(name = "detail")
    val detail: String? = null,
)
