package com.khasmek.flockyou

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.khasmek.flockyou.ui.PermissionGate
import com.khasmek.flockyou.ui.navigation.AppNavigation
import com.khasmek.flockyou.ui.theme.FlockYouTheme

/** Single-activity Compose host. Everything is gated behind BLE + location permissions. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FlockYouTheme {
                PermissionGate {
                    AppNavigation()
                }
            }
        }
    }
}
