package com.ritmute.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.ritmute.app.navigation.RitMuteApp
import com.ritmute.core.data.preferences.ThemeMode
import com.ritmute.core.data.preferences.UserPreferences
import com.ritmute.core.data.preferences.UserSettings
import com.ritmute.core.system.scheduler.SchedulerCoordinator
import com.ritmute.core.system.scheduler.Trigger
import com.ritmute.core.ui.theme.RitMuteTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var preferences: UserPreferences

    @Inject lateinit var coordinator: SchedulerCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        // Mandatory from targetSdk 35 and not opt-out-able; the whole UI is laid out with
        // window insets in mind rather than retrofitted later.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val settings by preferences.settings.collectAsState(initial = UserSettings())
            val darkTheme = when (settings.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            RitMuteTheme(darkTheme = darkTheme, dynamicColor = settings.dynamicColor) {
                RitMuteApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Another of the cheap reconciliation points that replace ACTION_USER_PRESENT.
        // Opening the app is, in practice, the most reliable recovery signal there is after
        // an OEM battery manager has stopped the process.
        // Off the main thread: reconcile() touches AudioManager, NotificationManager and
        // AlarmManager, all of which are blocking binder calls.
        lifecycleScope.launch(Dispatchers.Default) { coordinator.reconcile(Trigger.USER_INTERACTION) }
    }
}
