package com.khasmek.flockyou.detection

import androidx.room.Entity
import androidx.room.Index

/** Which radio produced the detection. */
enum class DetectionSource(val label: String) {
    /** The phone's own BLE scanner ([BleScanner]). Raven / SoundThinking and any BLE-visible Flock gear. */
    BLE("Phone BLE"),
    /** A XIAO ESP32-S3 running the flock-you WiFi promiscuous firmware, attached over USB serial. */
    ESP32_WIFI("ESP32 WiFi"),
}

/**
 * One unique detected device within one scan session. Room entity; the composite key means the
 * same MAC seen in two sessions is two rows, so sessions can be reviewed and deleted independently.
 *
 * Both detection sources write the same shape. BLE fills [ravenFirmware]; ESP32 fills [tier] and
 * [channel]; everything else is common.
 */
@Entity(
    tableName = "detected_devices",
    primaryKeys = ["sessionId", "macAddress"],
    indices = [Index("sessionId"), Index("lastSeen")],
)
data class DetectedDevice(
    val sessionId: String,
    val macAddress: String,
    val source: DetectionSource,
    val deviceName: String?,
    val detectionMethod: DetectionMethod,
    val deviceType: DeviceType,
    val confidence: Confidence,
    /** The prefix / pattern / company ID / UUID that triggered the match. */
    val matchedOn: String,
    /** "1.1.x", "1.2.x", "1.3.x", "?" for Ravens; null for everything else. */
    val ravenFirmware: String? = null,
    /** Firmware confidence tier 0-4 (ESP32 WiFi source only). */
    val tier: Int? = null,
    /** 2.4 GHz WiFi channel the frame was heard on (ESP32 WiFi source only). */
    val channel: Int? = null,
    val rssi: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val accuracyMeters: Float? = null,
    /** Epoch millis. */
    val firstSeen: Long,
    /** Epoch millis. */
    val lastSeen: Long,
    /** How many advertisements / frames matched for this MAC. */
    val sightings: Int = 1,
) {
    val displayName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: "Unknown"

    val hasLocation: Boolean
        get() = latitude != null && longitude != null
}
