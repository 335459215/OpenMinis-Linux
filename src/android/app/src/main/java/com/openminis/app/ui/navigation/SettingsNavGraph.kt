package com.openminis.app.ui.navigation

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.openminis.app.navigation.Routes
import com.openminis.app.ui.settings.AgentRuntimeSettingsScreen
import com.openminis.app.ui.settings.SettingsCategory
import com.openminis.app.ui.settings.SettingsCategoryScreen
import com.openminis.app.ui.settings.SettingsScreen

/** Home/category routes live apart from leaf features to keep the navigation root small. */
internal fun NavGraphBuilder.settingsHomeRoutes(navController: NavHostController) {
    composable(Routes.SETTINGS) {
        SettingsScreen(
            onBack = { navController.safePopBackStack() },
            onCategoryClick = { navController.safeNavigate(Routes.settingsCategory(it.id)) },
            onAppearanceClick = { navController.safeNavigate(Routes.APPEARANCE) },
            onNavigate = { navController.safeNavigate(it) },
        )
    }
    composable(
        route = Routes.SETTINGS_CATEGORY,
        arguments = listOf(navArgument("categoryId") { type = NavType.StringType }),
    ) { entry ->
        val category = SettingsCategory.fromId(entry.arguments?.getString("categoryId"))
        if (category == null) {
            // A retired category in a restored stack must not strand the user on a blank screen.
            LaunchedEffect(Unit) { navController.safePopBackStack() }
        } else {
            SettingsCategoryScreen(
                category = category,
                onBack = { navController.safePopBackStack() },
                onNavigate = { navController.safeNavigate(it) },
            )
        }
    }
    composable(Routes.AGENT_RUNTIME) {
        AgentRuntimeSettingsScreen(onBack = { navController.safePopBackStack() })
    }
}
