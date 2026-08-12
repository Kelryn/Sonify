package com.sonoritmo.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.sonoritmo.core.data.database.dao.ActivityLogDao
import com.sonoritmo.core.data.database.dao.AutomationStateDao
import com.sonoritmo.core.data.database.dao.ProfileDao
import com.sonoritmo.core.data.database.dao.ScheduleDao
import com.sonoritmo.core.data.database.dao.SnapshotDao
import com.sonoritmo.core.data.database.entity.ActivityLogEntity
import com.sonoritmo.core.data.database.entity.AudioSnapshotEntity
import com.sonoritmo.core.data.database.entity.AutomationStateEntity
import com.sonoritmo.core.data.database.entity.ProfileEntity
import com.sonoritmo.core.data.database.entity.ScheduleEntity

/**
 * The app's only database.
 *
 * `exportSchema = true` and the exported JSON under `core/data/schemas/` are **committed
 * on purpose**. An unversioned schema directory turns every future migration into
 * guesswork, and "lost my configuration after an update" is the single most common
 * complaint about the competing apps this one is meant to beat (RF-39).
 *
 * Rules for whoever bumps [VERSION] next:
 *
 *  1. Never `fallbackToDestructiveMigration()` in a release build. The user's schedules
 *     are the product.
 *  2. `AutoMigration` only for the genuinely trivial — a new column with a default, a new
 *     table, a rename. The moment values have to be *transformed*, write the migration by
 *     hand: an automatic migration moves the data without converting it, and the result
 *     passes schema validation while being wrong.
 *  3. Migrations run raw SQL only. A migration that calls a mapper breaks the day the
 *     mapper is refactored, and by then nobody remembers why.
 *  4. Never edit a published migration. Fix it with a new one.
 *
 * The `schedule_date_exceptions` table (RF-19, per-date skip/force) is deliberately
 * *not* created here even though it is designed: an unused empty table is debt, and its
 * addition makes a clean first real migration in v1.1.
 */
@Database(
    entities = [
        ProfileEntity::class,
        ScheduleEntity::class,
        ActivityLogEntity::class,
        AudioSnapshotEntity::class,
        AutomationStateEntity::class,
    ],
    // Literal on purpose: an annotation argument must be a compile-time constant, and
    // referring to the companion of the class being annotated is exactly the kind of
    // cleverness that breaks an annotation processor. VERSION below mirrors it and is
    // the constant the rest of the app (and the migration coverage test) reads.
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class SonoRitmoDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao

    abstract fun scheduleDao(): ScheduleDao

    abstract fun activityLogDao(): ActivityLogDao

    abstract fun snapshotDao(): SnapshotDao

    abstract fun automationStateDao(): AutomationStateDao

    companion object {
        const val VERSION = 1

        const val NAME = "sonoritmo.db"
    }
}
