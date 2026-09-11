package com.khasmek.birdwatch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.khasmek.birdwatch.ui.PermissionGate
import com.khasmek.birdwatch.ui.navigation.AppNavigation
import com.khasmek.birdwatch.ui.theme.BirdWatchTheme

/** Single-activity Compose host. Everything is gated behind BLE + location permissions. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BirdWatchTheme {
                PermissionGate {
                    AppNavigation()
                }
            }
        }
    }
}
