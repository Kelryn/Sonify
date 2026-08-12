package com.sonoritmo.core.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * What the diagnostics screen last concluded about whether scheduling actually works
 * on this device (RF-33).
 *
 * A cached verdict, not the truth: the truth is recomputed by the system layer from the
 * exact-alarm permission, the battery-optimisation state and the watchdog's repair
 * count. It is kept here purely so the settings screen can render a badge without doing
 * that work on every recomposition.
 */
enum class SchedulerHealth {
    /** Nothing has checked yet — a fresh install, before onboarding. */
    UNKNOWN,

    /** Exact alarms granted and no unexplained repairs. */
    HEALTHY,

    /** Working, but without precision guarantees: inexact alarms, or aggressive OEM. */
    DEGRADED,

    /** Something is actively preventing scheduling; the user has to act. */
    BROKEN,
}

/**
 * Everything the user can change that is *not* a profile, a schedule or automation state.
 *
 * ## What is deliberately not here
 *
 * The global pause, the manual activation and the applied profile all look like
 * "settings" and all belong in the database instead: they reference profiles and take
 * part in conflict resolution, and DataStore shares no transaction with Room, so a
 * deleted profile could not clean up after itself. The dividing line for this project is
 * exactly that — if it can influence which profile wins, it is a row, not a preference
 * (docs/02, amendment E-08).
 */
data class UserSettings(
    val onboardingCompleted: Boolean = false,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Material You. Off means the profile's own `colorSeed` drives the accent. */
    val dynamicColor: Boolean = true,
    /**
     * BCP-47 tag, or null to follow the system.
     *
     * Stored rather than derived so the choice survives a locale change made for one
     * app only, which is the whole point of the per-app language setting.
     */
    val languageTag: String? = null,
    /**
     * Maximum-reliability mode: schedule with `setAlarmClock` instead of
     * `setExactAndAllowWhileIdle`.
     *
     * Opt-in and off by default because the cost is visible and permanent: an alarm-clock
     * alarm puts a status-bar icon on the user's phone and is exempt from Doze batching,
     * so the user is trading a visible badge and battery for punctuality. That is their
     * call to make, not a default to impose.
     */
    val maxReliabilityMode: Boolean = false,
    /**
     * Applied when a window ends and the ending profile does not restore the baseline
     * (RF-15). Cleared to null if that profile is deleted, which falls back to restoring
     * the baseline — a dangling reference here would mean "restore something that no
     * longer exists", i.e. nothing at all, silently.
     */
    val defaultProfileUuid: String? = null,
    val schedulerHealth: SchedulerHealth = SchedulerHealth.UNKNOWN,
    val schedulerHealthCheckedAt: Instant? = null,
)

/**
 * UI and settings preferences, on DataStore.
 *
 * Reads never throw: a corrupt or unreadable preferences file degrades to defaults. The
 * alternative — an exception propagating out of a flow the whole UI collects — would mean
 * an app that cannot start because a settings file went bad, which is a far worse outcome
 * than a theme reverting to "system".
 */
@Singleton
class UserPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<UserSettings> = dataStore.data
        .catch { cause ->
            // Only I/O failures are recoverable this way. Anything else is a programming
            // error and must not be swallowed, or it will be debugged for a week.
            if (cause is IOException) emit(emptyPreferences()) else throw cause
        }
        .map { it.toSettings() }

    suspend fun get(): UserSettings = settings.first()

    suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[Keys.THEME_MODE] = mode.name }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { it[Keys.DYNAMIC_COLOR] = enabled }
    }

    /** @param tag null follows the system language. */
    suspend fun setLanguageTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(Keys.LANGUAGE_TAG) else prefs[Keys.LANGUAGE_TAG] = tag
        }
    }

    suspend fun setMaxReliabilityMode(enabled: Boolean) {
        dataStore.edit { it[Keys.MAX_RELIABILITY] = enabled }
    }

    suspend fun setDefaultProfileUuid(uuid: String?) {
        dataStore.edit { prefs ->
            if (uuid == null) prefs.remove(Keys.DEFAULT_PROFILE_UUID)
            else prefs[Keys.DEFAULT_PROFILE_UUID] = uuid
        }
    }

    suspend fun setSchedulerHealth(health: SchedulerHealth, checkedAt: Instant) {
        dataStore.edit { prefs ->
            prefs[Keys.SCHEDULER_HEALTH] = health.name
            prefs[Keys.SCHEDULER_HEALTH_CHECKED_AT] = checkedAt.toEpochMilli()
        }
    }

    private fun Preferences.toSettings(): UserSettings = UserSettings(
        onboardingCompleted = this[Keys.ONBOARDING_COMPLETED] ?: false,
        themeMode = readEnum(this[Keys.THEME_MODE], ThemeMode.entries, ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        languageTag = this[Keys.LANGUAGE_TAG],
        maxReliabilityMode = this[Keys.MAX_RELIABILITY] ?: false,
        defaultProfileUuid = this[Keys.DEFAULT_PROFILE_UUID],
        schedulerHealth = readEnum(
            raw = this[Keys.SCHEDULER_HEALTH],
            values = SchedulerHealth.entries,
            fallback = SchedulerHealth.UNKNOWN,
        ),
        schedulerHealthCheckedAt = this[Keys.SCHEDULER_HEALTH_CHECKED_AT]
            ?.let(Instant::ofEpochMilli),
    )

    /**
     * Unknown stored value ⇒ the default, never an exception.
     *
     * These two enums are purely presentational, so unlike the enums in the database
     * they can safely be stored by `name`; the fallback covers the only way that can go
     * wrong, which is reading a file written by a newer build after a downgrade.
     */
    private fun <E : Enum<E>> readEnum(raw: String?, values: List<E>, fallback: E): E =
        values.firstOrNull { it.name == raw } ?: fallback

    private object Keys {
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE_TAG = stringPreferencesKey("language_tag")
        val MAX_RELIABILITY = booleanPreferencesKey("max_reliability_mode")
        val DEFAULT_PROFILE_UUID = stringPreferencesKey("default_profile_uuid")
        val SCHEDULER_HEALTH = stringPreferencesKey("scheduler_health")
        val SCHEDULER_HEALTH_CHECKED_AT = longPreferencesKey("scheduler_health_checked_at")
    }

    companion object {
        /** Single file name; a second `DataStore` on it in the same process would throw. */
        const val FILE_NAME = "user_preferences"
    }
}
