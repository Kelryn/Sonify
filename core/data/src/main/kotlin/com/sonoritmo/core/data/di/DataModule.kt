package com.sonoritmo.core.data.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.sonoritmo.core.data.database.SonoRitmoDatabase
import com.sonoritmo.core.data.database.dao.ActivityLogDao
import com.sonoritmo.core.data.database.dao.AutomationStateDao
import com.sonoritmo.core.data.database.dao.ProfileDao
import com.sonoritmo.core.data.database.dao.ScheduleDao
import com.sonoritmo.core.data.database.dao.SnapshotDao
import com.sonoritmo.core.data.preferences.UserPreferences
import com.sonoritmo.core.data.repository.ActivityLogRepository
import com.sonoritmo.core.data.repository.ActivityLogRepositoryImpl
import com.sonoritmo.core.data.repository.AutomationRepository
import com.sonoritmo.core.data.repository.AutomationRepositoryImpl
import com.sonoritmo.core.data.repository.ProfileRepository
import com.sonoritmo.core.data.repository.ProfileRepositoryImpl
import com.sonoritmo.core.data.repository.ScheduleRepository
import com.sonoritmo.core.data.repository.ScheduleRepositoryImpl
import com.sonoritmo.core.data.repository.SchedulingWorldRepository
import com.sonoritmo.core.data.repository.SchedulingWorldRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Everything `:core:data` contributes to the graph.
 *
 * Note what is **not** here: `allowMainThreadQueries()`. The quick-settings tile, the
 * widget and the boot receivers all reach this layer, and letting any of them block the
 * main thread would turn a database read into an ANR at the worst possible moment — during
 * `BOOT_COMPLETED`, when the app is trying to prove it still works.
 */
@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SonoRitmoDatabase =
        Room.databaseBuilder(context, SonoRitmoDatabase::class.java, SonoRitmoDatabase.NAME)
            // Downgrades only. A downgrade means the user sideloaded an older APK by hand,
            // so there is no forward migration that could possibly exist; a *destructive
            // upgrade* fallback, on the other hand, would silently delete the schedules
            // that are the entire product.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides
    fun provideProfileDao(database: SonoRitmoDatabase): ProfileDao = database.profileDao()

    @Provides
    fun provideScheduleDao(database: SonoRitmoDatabase): ScheduleDao = database.scheduleDao()

    @Provides
    fun provideActivityLogDao(database: SonoRitmoDatabase): ActivityLogDao =
        database.activityLogDao()

    @Provides
    fun provideSnapshotDao(database: SonoRitmoDatabase): SnapshotDao = database.snapshotDao()

    @Provides
    fun provideAutomationStateDao(database: SonoRitmoDatabase): AutomationStateDao =
        database.automationStateDao()

    /**
     * Exactly one `DataStore` per file per process — constructing a second one on the same
     * file throws — which is why this is a `@Singleton` and why nothing else in the module
     * ever builds one.
     */
    @Provides
    @Singleton
    fun providePreferencesDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        // A corrupt preferences file must not be able to stop the app from starting. The
        // cost of resetting it is a theme going back to "system"; the cost of throwing
        // here is an app that cannot be opened at all.
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile(UserPreferences.FILE_NAME) },
    )

    // ── Domain ports ─────────────────────────────────────────────────────────
    //
    // The domain forbids itself from reading a clock, a zone or a random source directly,
    // precisely so that DST, time-zone changes and identifier generation are testable.
    // These are the only implementations that actually touch the system.

    // TimeSource, ZoneProvider and UuidGenerator are NOT provided here.
    //
    // :core:system binds them (SystemTimeSource, SystemZoneProvider, RandomUuidGenerator)
    // and :app depends on both modules, so providing them twice in the same component is a
    // Dagger DuplicateBindings failure. :core:data consumes them from the graph, which is
    // the correct direction: the data layer needs a clock, it does not own one.
}

/**
 * Interface-to-implementation wiring.
 *
 * Separate from [DataModule] because `@Binds` requires an abstract module; keeping both in
 * one file keeps the whole of this module's graph readable in one place.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataBindingsModule {

    @Binds
    abstract fun bindProfileRepository(impl: ProfileRepositoryImpl): ProfileRepository

    @Binds
    abstract fun bindScheduleRepository(impl: ScheduleRepositoryImpl): ScheduleRepository

    @Binds
    abstract fun bindActivityLogRepository(impl: ActivityLogRepositoryImpl): ActivityLogRepository

    @Binds
    abstract fun bindAutomationRepository(impl: AutomationRepositoryImpl): AutomationRepository

    @Binds
    abstract fun bindSchedulingWorldRepository(
        impl: SchedulingWorldRepositoryImpl,
    ): SchedulingWorldRepository
}
