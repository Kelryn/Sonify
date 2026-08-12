package com.sonoritmo.core.system.scheduler

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton

/** Marks the private `DataStore` this module owns, so it can never collide with `:core:data`'s. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SchedulerHealthPreferences

/**
 * The answer to "is the automation actually working?", in one object.
 *
 * Lives in `DataStore` rather than in the database on purpose, and it is the exception
 * that proves the project's rule: things that take part in conflict resolution live in
 * Room (amendment E-08), and this takes part in nothing. It is telemetry about the
 * platform, it is rewritten on every pass, and losing it costs nothing.
 */
data class SchedulerHealth(
    val nextScheduledAt: Instant? = null,
    val mode: SchedulerMode = SchedulerMode.INEXACT,
    val lastReconciliationAt: Instant? = null,
    /** How often the watchdog found the device wrong and put it back. A health KPI (RF-33). */
    val repairsCount: Int = 0,
    val lastForceStopDetectedAt: Instant? = null,
    /** The user's opt-in to `setAlarmClock`, from the diagnostics screen. */
    val preferAlarmClock: Boolean = false,
    val lastError: String? = null,
)

@Singleton
class SchedulerHealthStore @Inject constructor(
    @SchedulerHealthPreferences private val dataStore: DataStore<Preferences>,
) {

    /**
     * A corrupted or unreadable preferences file must not take the reconciler down with it:
     * the scheduler works perfectly well with default health values, and the alternative is
     * an app that cannot schedule anything because it cannot read its own telemetry.
     */
    val health: Flow<SchedulerHealth> = dataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toHealth() }

    suspend fun current(): SchedulerHealth = health.first()

    suspend fun recordScheduled(at: Instant, mode: SchedulerMode) {
        dataStore.edit { preferences ->
            preferences[KEY_NEXT_AT] = at.toEpochMilli()
            preferences[KEY_MODE] = mode.name
        }
    }

    suspend fun recordPass(at: Instant, repaired: Boolean, error: String?) {
        dataStore.edit { preferences ->
            preferences[KEY_LAST_RECONCILE_AT] = at.toEpochMilli()
            if (repaired) {
                preferences[KEY_REPAIRS] = (preferences[KEY_REPAIRS] ?: 0) + 1
            }
            if (error == null) {
                preferences.remove(KEY_LAST_ERROR)
            } else {
                preferences[KEY_LAST_ERROR] = error
            }
        }
    }

    suspend fun setPreferAlarmClock(enabled: Boolean) {
        dataStore.edit { preferences -> preferences[KEY_PREFER_ALARM_CLOCK] = enabled }
    }

    /**
     * Records a force stop detected through `ApplicationStartInfo` (API 35+).
     *
     * There is no technical defence against a force stop — it cancels every pending intent
     * and disables the widgets, and neither the alarm nor `WorkManager` recovers on its
     * own. All the app can do is notice it happened and be honest about the gap.
     * See docs/02, risk N3.
     */
    suspend fun recordForceStop(at: Instant) {
        dataStore.edit { preferences -> preferences[KEY_FORCE_STOP_AT] = at.toEpochMilli() }
    }

    private fun Preferences.toHealth(): SchedulerHealth = SchedulerHealth(
        nextScheduledAt = this[KEY_NEXT_AT]?.let(Instant::ofEpochMilli),
        mode = this[KEY_MODE]?.let(::modeOrDefault) ?: SchedulerMode.INEXACT,
        lastReconciliationAt = this[KEY_LAST_RECONCILE_AT]?.let(Instant::ofEpochMilli),
        repairsCount = this[KEY_REPAIRS] ?: 0,
        lastForceStopDetectedAt = this[KEY_FORCE_STOP_AT]?.let(Instant::ofEpochMilli),
        preferAlarmClock = this[KEY_PREFER_ALARM_CLOCK] ?: false,
        lastError = this[KEY_LAST_ERROR],
    )

    /** Stored by name, never by ordinal: reordering the enum must not rewrite history. */
    private fun modeOrDefault(name: String): SchedulerMode =
        SchedulerMode.entries.firstOrNull { it.name == name } ?: SchedulerMode.INEXACT

    companion object {
        /** File name of this module's private preferences store. */
        const val FILE_NAME = "sonoritmo_scheduler_health"

        private val KEY_NEXT_AT = longPreferencesKey("next_scheduled_at")
        private val KEY_MODE = stringPreferencesKey("scheduler_mode")
        private val KEY_LAST_RECONCILE_AT = longPreferencesKey("last_reconciliation_at")
        private val KEY_REPAIRS = intPreferencesKey("repairs_count")
        private val KEY_FORCE_STOP_AT = longPreferencesKey("last_force_stop_at")
        private val KEY_PREFER_ALARM_CLOCK = booleanPreferencesKey("prefer_alarm_clock")
        private val KEY_LAST_ERROR = stringPreferencesKey("last_error")
    }
}
