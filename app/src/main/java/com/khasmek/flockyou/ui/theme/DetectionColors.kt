package com.khasmek.flockyou.ui.theme

import androidx.compose.ui.graphics.Color
import com.khasmek.flockyou.detection.DetectionSource
import com.khasmek.flockyou.detection.DeviceCategory
import com.khasmek.flockyou.detection.DeviceType

/** Fixed accent colours per device category so a Flock, a Raven and a body cam are recognisable at a glance. */
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
}
