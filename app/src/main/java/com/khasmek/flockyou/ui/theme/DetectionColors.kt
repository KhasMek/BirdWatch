package com.khasmek.flockyou.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.khasmek.flockyou.detection.DetectionSource
import com.khasmek.flockyou.detection.DeviceType

/** Fixed accent colours per device type so a Flock and a Raven are recognisable at a glance. */
object DetectionColors {
    val Flock = Color(0xFFE65100)        // deep orange
    val Raven = Color(0xFF6A1B9A)        // purple
    val SoundThinking = Color(0xFF8E24AA)
    val Ble = Color(0xFF1565C0)
    val Esp32 = Color(0xFF2E7D32)
    val OnAccent = Color.White

    @Composable
    fun forType(type: DeviceType): Color = when (type) {
        DeviceType.FLOCK -> Flock
        DeviceType.RAVEN -> Raven
        DeviceType.SOUNDTHINKING -> SoundThinking
    }

    @Composable
    fun forSource(source: DetectionSource): Color = when (source) {
        DetectionSource.BLE -> Ble
        DetectionSource.ESP32_WIFI -> Esp32
    }
}
