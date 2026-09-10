package com.khasmek.flockyou.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.khasmek.flockyou.ui.screens.DashboardScreen
import com.khasmek.flockyou.ui.screens.MapScreen
import com.khasmek.flockyou.ui.screens.PreviousSessionScreen
import com.khasmek.flockyou.ui.screens.SettingsScreen

/** The four top-level destinations shown in the bottom navigation bar. */
enum class AppTab(val route: String, val label: String, val icon: ImageVector) {
    Dashboard("dashboard", "Dashboard", Icons.Default.Radar),
    Map("map", "Map", Icons.Default.Map),
    Sessions("sessions", "Sessions", Icons.Default.History),
    Settings("settings", "Settings", Icons.Default.Settings)
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
                    val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = { navController.navigateToTab(tab) },
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
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(AppTab.Dashboard.route) { DashboardScreen() }
            composable(AppTab.Map.route) {
                MapScreen(onOpenSettings = { navController.navigateToTab(AppTab.Settings) })
            }
            composable(AppTab.Sessions.route) { PreviousSessionScreen() }
            composable(AppTab.Settings.route) { SettingsScreen() }
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
