package com.khasmek.flockyou.detection

import androidx.room.Entity
import androidx.room.Index

/** Which radio produced the detection. */
enum class DetectionSource(val label: String) {
    /** The phone's own BLE scanner ([BleScanner]). Raven / SoundThinking and any BLE-visible Flock gear. */
    BLE("Phone BLE"),
    /** A XIAO ESP32-S3 running the flock-you WiFi promiscuous firmware, attached over USB serial. */
    ESP32_WIFI("ESP32 WiFi"),
    /** The phone's own WiFi radio scanning visible access points (BSSID OUI matching). */
    PHONE_WIFI("Phone WiFi"),
}

/**
 * One unique detected device within one scan session. Room entity; the composite key means the
 * same MAC seen in two sessions is two rows, so sessions can be reviewed and deleted independently.
 *
 * All detection sources write the same shape. BLE fills [ravenFirmware]; ESP32 fills [tier] and
 * [channel]; Remote ID fills the `uasId` / `operatorId` / `target*` / `operator*` columns
 * (schema v2); everything else is common.
 *
 * [latitude]/[longitude] are always the **phone's** position at the sighting. A Remote ID drone
 * additionally reports its own position in [targetLatitude]/[targetLongitude].
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

    // ---- Remote ID (ASTM F3411), schema v2. Null for every other detection. ----
    /** UAS serial number / registration from the Basic ID message. */
    val uasId: String? = null,
    /** Operator registration id from the Operator ID message. */
    val operatorId: String? = null,
    /** The drone's self-reported position (Location message). */
    val targetLatitude: Double? = null,
    val targetLongitude: Double? = null,
    /** Geodetic altitude in metres from the Location message. */
    val targetAltitudeM: Double? = null,
    /** Operator / takeoff position from the System message. */
    val operatorLatitude: Double? = null,
    val operatorLongitude: Double? = null,
) {
    val displayName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: uasId?.takeIf { it.isNotBlank() } ?: "Unknown"

    val hasLocation: Boolean
        get() = latitude != null && longitude != null

    val hasTargetLocation: Boolean
        get() = targetLatitude != null && targetLongitude != null

    val hasOperatorLocation: Boolean
        get() = operatorLatitude != null && operatorLongitude != null

    val isRemoteId: Boolean
        get() = detectionMethod == DetectionMethod.REMOTE_ID_BLE || detectionMethod == DetectionMethod.REMOTE_ID_WIFI

    /** Fold a decoded Remote ID payload into this row, keeping earlier values where the new broadcast omits them. */
    fun withRemoteId(p: RemoteId.Payload?): DetectedDevice {
        if (p == null) return this
        val loc = p.location
        val sys = p.system
        return copy(
            uasId = p.basicId?.uasId?.takeIf { it.isNotBlank() } ?: uasId,
            operatorId = p.operatorId?.operatorId?.takeIf { it.isNotBlank() } ?: operatorId,
            targetLatitude = loc?.latitude ?: targetLatitude,
            targetLongitude = loc?.longitude ?: targetLongitude,
            targetAltitudeM = loc?.altitudeGeodeticM ?: targetAltitudeM,
            operatorLatitude = sys?.operatorLatitude ?: operatorLatitude,
            operatorLongitude = sys?.operatorLongitude ?: operatorLongitude,
        )
    }
}
