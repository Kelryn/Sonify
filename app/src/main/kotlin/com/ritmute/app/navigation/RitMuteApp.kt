package com.ritmute.app.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ritmute.app.R
import com.ritmute.feature.profiles.navigation.ROUTE_PROFILES
import com.ritmute.feature.profiles.navigation.navigateToProfileEditor
import com.ritmute.feature.profiles.navigation.profilesGraph
import com.ritmute.feature.tools.navigation.ROUTE_DIAGNOSTICS
import com.ritmute.feature.tools.navigation.ROUTE_HISTORY
import com.ritmute.feature.tools.navigation.ROUTE_SETTINGS
import com.ritmute.feature.tools.navigation.toolsGraph

private data class TopLevelDestination(
    val route: String,
    val icon: ImageVector,
    val labelRes: Int,
)

private val TOP_LEVEL = listOf(
    TopLevelDestination(ROUTE_PROFILES, Icons.Filled.Tune, R.string.nav_profiles),
    TopLevelDestination(ROUTE_HISTORY, Icons.Filled.History, R.string.nav_history),
    TopLevelDestination(ROUTE_SETTINGS, Icons.Filled.Settings, R.string.nav_settings),
)

/**
 * The whole navigation graph.
 *
 * Three tabs and a maximum of two levels from any of them: nothing in this app needs a
 * third hop. Back is handled entirely by Navigation Compose — `onBackPressed` is not
 * overridden anywhere, because from targetSdk 36 it is no longer invoked and predictive
 * back is on by default.
 */
@Composable
fun RitMuteApp(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = TOP_LEVEL.any { top ->
        currentDestination?.hierarchy?.any { it.route == top.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    TOP_LEVEL.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTopLevel(destination.route) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(stringResource(destination.labelRes)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_PROFILES,
            // Only the bottom inset is consumed here. Each screen brings its own Scaffold and
            // handles its own top bar, so passing the whole PaddingValues down would inset the
            // top twice; but a screen's Scaffold cannot know about the navigation bar below it,
            // and without this its floating action button is laid out underneath the tabs.
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = padding.calculateBottomPadding()),
        ) {
            profilesGraph(
                onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) },
                onEditProfile = { uuid -> navController.navigateToProfileEditor(uuid) },
                onEditorDone = { navController.popBackStack() },
            )
            toolsGraph(onOpenDiagnostics = { navController.navigate(ROUTE_DIAGNOSTICS) })
        }
    }
}

/** Tab switching keeps each tab's own back stack and never piles duplicates on top. */
private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
