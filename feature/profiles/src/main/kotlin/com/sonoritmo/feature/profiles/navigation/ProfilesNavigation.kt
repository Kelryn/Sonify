package com.sonoritmo.feature.profiles.navigation

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navDeepLink
import com.sonoritmo.feature.profiles.editor.ProfileEditorScreen
import com.sonoritmo.feature.profiles.editor.ProfileEditorViewModel
import com.sonoritmo.feature.profiles.list.ProfilesScreen

const val ROUTE_PROFILES = "profiles"
private const val ROUTE_EDITOR_BASE = "profile-editor"
const val ROUTE_PROFILE_EDITOR = "$ROUTE_EDITOR_BASE?${ProfileEditorViewModel.ARG_PROFILE_UUID}={${ProfileEditorViewModel.ARG_PROFILE_UUID}}"

fun profileEditorRoute(profileUuid: String?): String =
    if (profileUuid == null) {
        ROUTE_EDITOR_BASE
    } else {
        "$ROUTE_EDITOR_BASE?${ProfileEditorViewModel.ARG_PROFILE_UUID}=$profileUuid"
    }

fun NavController.navigateToProfileEditor(profileUuid: String?) =
    navigate(profileEditorRoute(profileUuid))

/**
 * Deep links use the `sonoritmo://` scheme rather than an `https://` one.
 *
 * That is not a style choice: the app declares no `INTERNET` permission, and an `http`
 * deep link in the manifest would invite the reasonable question of what it talks to.
 */
fun NavGraphBuilder.profilesGraph(
    onOpenDiagnostics: () -> Unit,
    onEditProfile: (String?) -> Unit,
    onEditorDone: () -> Unit,
) {
    composable(
        route = ROUTE_PROFILES,
        deepLinks = listOf(navDeepLink { uriPattern = "sonoritmo://profiles" }),
    ) {
        ProfilesScreen(
            onEditProfile = onEditProfile,
            onOpenDiagnostics = onOpenDiagnostics,
        )
    }

    composable(
        route = ROUTE_PROFILE_EDITOR,
        arguments = listOf(
            navArgument(ProfileEditorViewModel.ARG_PROFILE_UUID) {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            },
        ),
        deepLinks = listOf(navDeepLink { uriPattern = "sonoritmo://profile/{${ProfileEditorViewModel.ARG_PROFILE_UUID}}" }),
    ) {
        ProfileEditorScreen(onDone = onEditorDone)
    }
}
