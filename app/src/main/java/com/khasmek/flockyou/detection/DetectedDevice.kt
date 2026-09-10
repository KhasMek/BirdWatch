package com.khasmek.flockyou.detection

/**
 * One unique detected device, keyed by MAC address. In-memory model for Phase 2; Phase 3 turns
 * this into the Room entity and fills in location + session.
 */
data class DetectedDevice(
    val macAddress: String,
    val deviceName: String?,
    val detectionMethod: DetectionMethod,
    val deviceType: DeviceType,
    val confidence: Confidence,
    /** The prefix / pattern / company ID / UUID that triggered the match. */
    val matchedOn: String,
    /** "1.1.x", "1.2.x", "1.3.x", "?" for Ravens; null for everything else. */
    val ravenFirmware: String?,
    val rssi: Int,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Epoch millis. */
    val firstSeen: Long,
    /** Epoch millis. */
    val lastSeen: Long,
    /** How many advertisements matched for this MAC. */
    val sightings: Int = 1,
    val sessionId: String = "",
) {
    val displayName: String
        get() = deviceName?.takeIf { it.isNotBlank() } ?: "Unknown"
}
