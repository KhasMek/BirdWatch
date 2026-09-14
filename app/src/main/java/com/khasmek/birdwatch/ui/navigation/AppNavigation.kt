package com.khasmek.birdwatch.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.khasmek.birdwatch.ui.screens.AboutScreen
import com.khasmek.birdwatch.ui.screens.DashboardScreen
import com.khasmek.birdwatch.ui.screens.MapScreen
import com.khasmek.birdwatch.ui.screens.PreviousSessionScreen
import com.khasmek.birdwatch.ui.screens.SessionDetailScreen
import com.khasmek.birdwatch.ui.screens.SettingsScreen

/** The four top-level destinations shown in the bottom navigation bar. */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Default.Radar),
    Map("map", "Map", Icons.Default.Map),
    Sessions("sessions", "Sessions", Icons.Default.History),
    Settings("settings", "Settings", Icons.Default.Settings)
}

object Routes {
    const val SESSION_DETAIL = "sessions/{sessionId}"
    fun sessionDetail(sessionId: String) = "sessions/$sessionId"
    const val ABOUT = "settings/about"
}

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        // Each screen owns its top app bar and therefore the status-bar inset; the bottom bar
        // handles the navigation-bar inset itself. Consuming nothing here avoids a double gap.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { tab ->
                    // A tab is selected when the current route is the tab itself or nested under it
                    // (e.g. sessions/{id} keeps the Sessions tab highlighted).
                    val selected = currentDestination?.hierarchy?.any {
                        it.route == tab.route || it.route?.startsWith(tab.route + "/") == true
                    } == true
                    NavigationBarItem(
                        selected = selected,
                        // Re-tapping the selected tab returns to its root (e.g. from a session's detail).
                        onClick = {
                            if (selected) navController.popBackStack(tab.route, inclusive = false)
                            else navController.navigateToTab(tab)
                        },
                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                        label = { Text(tab.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = AppTab.Dashboard.route,
            // consumeWindowInsets: the bottom bar's height is applied as padding here, so each
            // screen's own Scaffold must not add the navigation-bar inset a second time.
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
        ) {
            composable(AppTab.Dashboard.route) { DashboardScreen() }
            composable(AppTab.Map.route) {
                MapScreen(onOpenSettings = { navController.navigateToTab(AppTab.Settings) })
            }
            composable(AppTab.Sessions.route) {
                PreviousSessionScreen(onOpenSession = { navController.navigate(Routes.sessionDetail(it)) })
            }
            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { entry ->
                val id = entry.arguments?.getString("sessionId").orEmpty()
                SessionDetailScreen(sessionId = id, onBack = { navController.popBackStack() })
            }
            composable(AppTab.Settings.route) {
                SettingsScreen(onOpenAbout = { navController.navigate(Routes.ABOUT) })
            }
            composable(Routes.ABOUT) { AboutScreen(onBack = { navController.popBackStack() }) }
        }
    }
}

private fun NavHostController.navigateToTab(tab: AppTab) {
    navigate(tab.route) {
        // Pop up to the start destination to avoid building a large back stack when switching tabs.
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
