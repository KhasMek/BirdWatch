package com.khasmek.birdwatch.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.detection.DeviceType

/**
 * Fixed accent colours per device category so a Flock, a Raven and a body cam are recognisable at
 * a glance. Two sets: the saturated colours are fills (badges, map lines) with white on top; the
 * `text*` variants are for text and icons drawn directly on the surface, and switch to lighter
 * tints in dark theme, where the saturated purple / blue / slate fall below 3:1 against the M3
 * dark containers. Night is when this app gets used.
 */
object DetectionColors {
    val Flock = Color(0xFFE65100)          // deep orange
    val Raven = Color(0xFF6A1B9A)          // purple
    val LawEnforcement = Color(0xFF1565C0) // blue
    val Wearable = Color(0xFF00897B)       // teal
    val Drone = Color(0xFF546E7A)          // blue-grey
    val Ble = Color(0xFF1565C0)
    val Esp32 = Color(0xFF2E7D32)
    val PhoneWifi = Color(0xFF6D4C41)
    val OnAccent = Color.White

    // Dark-surface tints (Material 300/200 shades): all >= 4.5:1 on #211F26.
    private val FlockDark = Color(0xFFFFB74D)
    private val RavenDark = Color(0xFFCE93D8)
    private val LawEnforcementDark = Color(0xFF64B5F6)
    private val WearableDark = Color(0xFF4DB6AC)
    private val DroneDark = Color(0xFF90A4AE)
    private val BleDark = Color(0xFF64B5F6)
    private val Esp32Dark = Color(0xFF81C784)
    private val PhoneWifiDark = Color(0xFFBCAAA4)

    fun forCategory(category: DeviceCategory): Color = when (category) {
        DeviceCategory.FLOCK_ALPR -> Flock
        DeviceCategory.GUNSHOT_DETECTOR -> Raven
        DeviceCategory.LAW_ENFORCEMENT -> LawEnforcement
        DeviceCategory.WEARABLE_CAMERA -> Wearable
        DeviceCategory.DRONE -> Drone
    }

    fun forType(type: DeviceType): Color = forCategory(type.category)

    fun forSource(source: DetectionSource): Color = when (source) {
        DetectionSource.BLE -> Ble
        DetectionSource.ESP32_WIFI -> Esp32
        DetectionSource.PHONE_WIFI -> PhoneWifi
    }

    /** Whether the current Material scheme is dark (follows the applied theme, not just the system). */
    @Composable
    private fun isDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

    /** Category colour for text and icons on the surface; readable in both themes. */
    @Composable
    fun textForCategory(category: DeviceCategory): Color =
        if (!isDark()) forCategory(category) else when (category) {
            DeviceCategory.FLOCK_ALPR -> FlockDark
            DeviceCategory.GUNSHOT_DETECTOR -> RavenDark
            DeviceCategory.LAW_ENFORCEMENT -> LawEnforcementDark
            DeviceCategory.WEARABLE_CAMERA -> WearableDark
            DeviceCategory.DRONE -> DroneDark
        }

    @Composable
    fun textForSource(source: DetectionSource): Color =
        if (!isDark()) forSource(source) else when (source) {
            DetectionSource.BLE -> BleDark
            DetectionSource.ESP32_WIFI -> Esp32Dark
            DetectionSource.PHONE_WIFI -> PhoneWifiDark
        }
}
