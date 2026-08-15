package com.ritmute.core.system.di

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.ritmute.core.domain.port.TimeSource
import com.ritmute.core.domain.port.UuidGenerator
import com.ritmute.core.domain.port.ZoneProvider
import com.ritmute.core.system.audio.AudioCapabilities
import com.ritmute.core.system.audio.AudioCapabilitiesImpl
import com.ritmute.core.system.audio.AudioStateSnapshotter
import com.ritmute.core.system.audio.AudioStateSnapshotterImpl
import com.ritmute.core.system.audio.CallStateProbe
import com.ritmute.core.system.audio.CallStateProbeImpl
import com.ritmute.core.system.audio.MediaActivityProbe
import com.ritmute.core.system.audio.MediaActivityProbeImpl
import com.ritmute.core.system.audio.ProfileAudioApplier
import com.ritmute.core.system.audio.ProfileAudioApplierImpl
import com.ritmute.core.system.audio.RingerController
import com.ritmute.core.system.audio.RingerControllerImpl
import com.ritmute.core.system.audio.VolumeController
import com.ritmute.core.system.audio.VolumeControllerImpl
import com.ritmute.core.system.dnd.DndController
import com.ritmute.core.system.dnd.DndControllerImpl
import com.ritmute.core.system.dnd.ZenRuleCleaner
import com.ritmute.core.system.dnd.ZenRuleCleanerImpl
import com.ritmute.core.system.dnd.ZenRuleRegistrar
import com.ritmute.core.system.dnd.ZenRuleRegistrarImpl
import com.ritmute.core.system.ports.RandomUuidGenerator
import com.ritmute.core.system.ports.SystemTimeSource
import com.ritmute.core.system.ports.SystemZoneProvider
import com.ritmute.core.system.scheduler.AlarmScheduler
import com.ritmute.core.system.scheduler.AlarmSchedulerImpl
import com.ritmute.core.system.scheduler.SchedulerHealthPreferences
import com.ritmute.core.system.scheduler.SchedulerHealthStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Platform singletons.
 *
 * Every system service is injected rather than fetched from a `Context` at the point of use.
 * That is what lets `:core:system` be covered by instrumented tests with permissions granted
 * *and* revoked (F6), which is the only honest way to test an adapter layer whose entire job
 * is coping with refusals.
 */
@Module
@InstallIn(SingletonComponent::class)
object SystemServicesModule {

    /**
     * `checkNotNull` and not a fallback: a device without an `AudioManager` cannot run an
     * app about volumes, and pretending otherwise would replace a loud failure at startup
     * with a silent one at 23:00.
     */
    @Provides
    @Singleton
    fun provideAudioManager(@ApplicationContext context: Context): AudioManager =
        checkNotNull(context.getSystemService(AudioManager::class.java)) { "AudioManager unavailable" }

    @Provides
    @Singleton
    fun provideNotificationManager(@ApplicationContext context: Context): NotificationManager =
        checkNotNull(context.getSystemService(NotificationManager::class.java)) {
            "NotificationManager unavailable"
        }

    @Provides
    @Singleton
    fun provideAlarmManager(@ApplicationContext context: Context): AlarmManager =
        checkNotNull(context.getSystemService(AlarmManager::class.java)) { "AlarmManager unavailable" }

    /**
     * This module's own, private preferences file.
     *
     * Deliberately not shared with `:core:data`'s DataStore: the two modules have no
     * dependency on each other, and a shared file would create one through the back door.
     * Corruption is handled by starting again from empty — losing telemetry is free, whereas
     * an unreadable file that propagated an exception would stop the app scheduling
     * anything at all.
     */
    @Provides
    @Singleton
    @SchedulerHealthPreferences
    fun provideSchedulerHealthDataStore(
        @ApplicationContext context: Context,
    ): DataStore<Preferences> = PreferenceDataStoreFactory.create(
        corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
        produceFile = { context.preferencesDataStoreFile(SchedulerHealthStore.FILE_NAME) },
    )
}

/**
 * Interface bindings.
 *
 * ## What is deliberately missing
 *
 * `SchedulingWorldSource` and `ReconciliationSink` are **not** bound here, and that is the
 * architectural rule made visible: `:core:system` does not depend on `:core:data`. The
 * reconciler receives the world and hands back the consequences; `:app` binds both ends to
 * the repositories. A consumer that forgets to bind them gets a Hilt compile-time error,
 * which is exactly the right moment to find out.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SystemModule {

    @Binds
    abstract fun bindTimeSource(impl: SystemTimeSource): TimeSource

    @Binds
    abstract fun bindZoneProvider(impl: SystemZoneProvider): ZoneProvider

    @Binds
    abstract fun bindUuidGenerator(impl: RandomUuidGenerator): UuidGenerator

    @Binds
    abstract fun bindAudioCapabilities(impl: AudioCapabilitiesImpl): AudioCapabilities

    @Binds
    abstract fun bindVolumeController(impl: VolumeControllerImpl): VolumeController

    @Binds
    abstract fun bindRingerController(impl: RingerControllerImpl): RingerController

    @Binds
    abstract fun bindAudioStateSnapshotter(impl: AudioStateSnapshotterImpl): AudioStateSnapshotter

    @Binds
    abstract fun bindCallStateProbe(impl: CallStateProbeImpl): CallStateProbe

    @Binds
    abstract fun bindMediaActivityProbe(impl: MediaActivityProbeImpl): MediaActivityProbe

    @Binds
    abstract fun bindProfileAudioApplier(impl: ProfileAudioApplierImpl): ProfileAudioApplier

    /**
     * The implementation is annotated `@RequiresApi(29)` because every one of its methods
     * calls an API from that level. Binding it unconditionally is safe: its constructor only
     * stores two references, and `DndController` is the sole caller — it routes by
     * `Build.VERSION.SDK_INT` and never touches the registrar below API 29.
     */
    @Suppress("NewApi")
    @Binds
    abstract fun bindZenRuleRegistrar(impl: ZenRuleRegistrarImpl): ZenRuleRegistrar

    @Binds
    abstract fun bindZenRuleCleaner(impl: ZenRuleCleanerImpl): ZenRuleCleaner

    @Binds
    abstract fun bindDndController(impl: DndControllerImpl): DndController

    @Binds
    abstract fun bindAlarmScheduler(impl: AlarmSchedulerImpl): AlarmScheduler
}
