package com.khasmek.flockyou.ui.screens

import androidx.compose.runtime.Composable

/** Wardriving map. Gated on a user-provided Google Maps API key. Implemented in Phase 6. */
@Composable
fun MapScreen(onOpenSettings: () -> Unit) {
    PlaceholderScreen(
        title = "Map",
        subtitle = "Enter your Google Maps API key in Settings to enable the map view.",
        actionLabel = "Open Settings",
        onAction = onOpenSettings
    )
}
