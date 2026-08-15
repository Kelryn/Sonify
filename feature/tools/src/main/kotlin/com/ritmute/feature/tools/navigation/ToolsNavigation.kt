package com.ritmute.feature.tools.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import com.ritmute.feature.tools.diagnostics.DiagnosticsScreen
import com.ritmute.feature.tools.history.HistoryScreen
import com.ritmute.feature.tools.settings.SettingsScreen

const val ROUTE_HISTORY = "history"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_DIAGNOSTICS = "diagnostics"

fun NavGraphBuilder.toolsGraph(onOpenDiagnostics: () -> Unit) {
    composable(
        route = ROUTE_HISTORY,
        deepLinks = listOf(navDeepLink { uriPattern = "ritmute://history" }),
    ) {
        HistoryScreen()
    }

    composable(route = ROUTE_SETTINGS) {
        SettingsScreen(onOpenDiagnostics = onOpenDiagnostics)
    }

    composable(
        route = ROUTE_DIAGNOSTICS,
        // Reached from the "permission missing" notification and from the quick-settings
        // tile when it is showing as unavailable.
        deepLinks = listOf(navDeepLink { uriPattern = "ritmute://diagnostics" }),
    ) {
        DiagnosticsScreen()
    }
}
