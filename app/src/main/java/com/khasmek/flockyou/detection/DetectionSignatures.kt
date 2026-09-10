package com.khasmek.flockyou.detection

/**
 * Detection signatures ported verbatim from the flock-you ESP32 firmware.
 *
 * Primary source: `src/main.cpp` of colonelpanichacks/flock-you at commit 6c6930b (2026-04-20),
 * the last BLE-era revision before the repo was rewritten as a WiFi promiscuous sniffer.
 * Supplementary source: the current `main.cpp` (dev branch, 2026-07-16 OUI sync), whose newer
 * Flock Cam OUIs are merged into [FLOCK_MAC_PREFIXES_2026].
 *
 * All MAC prefixes are lowercase, colon-separated, first three octets ("aa:bb:cc").
 * All UUIDs are lowercase 128-bit canonical form.
 *
 * This file has no Android dependencies so it can be unit-tested on the JVM.
 */
object DetectionSignatures {

    // ------------------------------------------------------------------
    // Method 1: MAC OUI prefixes
    // ------------------------------------------------------------------

    /** Flock Safety - high-confidence OUIs (direct registration or exclusive use). */
    val FLOCK_MAC_PREFIXES: Set<String> = setOf(
        // FS Ext Battery devices
        "58:8e:81", "cc:cc:cc", "ec:1b:bd", "90:35:ea", "04:0d:84",
        "f0:82:c0", "1c:34:f1", "38:5b:44", "94:34:69", "b4:e3:f9",
        // Flock WiFi devices
        "70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "14:5a:fc",
        "74:4c:a1", "08:3a:88", "9c:2f:9d", "94:08:53", "e4:aa:ea",
        // Flock Safety (direct IEEE registration)
        "b4:1e:52",
    )

    /**
     * Flock Cam OUIs added by @NitekryDPaul's 2026-07-16 research (nite-oui-collection) and
     * DeFlockJoplin, taken from the current WiFi-era `main.cpp`. Only prefixes not already listed
     * above or in the contract-manufacturer list appear here. They were validated on 2.4 GHz WiFi
     * frames; the BLE-era firmware already mixed "Flock WiFi devices" into its BLE matcher, so the
     * same precedent is followed here.
     */
    val FLOCK_MAC_PREFIXES_2026: Set<String> = setOf(
        "b8:35:32", "c0:35:32", "24:b2:b9", "e0:4f:43", "b8:1e:a4",
        "70:08:94", "3c:71:bf", "58:00:e3", "5c:93:a2", "64:6e:69",
        "48:27:ea", "a4:cf:12", "14:b5:cd",
        "82:6b:f2", // contributed by DeFlockJoplin
    )

    /**
     * Flock Safety contract manufacturers - lower confidence alone.
     * These OUIs belong to Liteon Technology and USI (Universal Scientific Industrial), which
     * produce Flock hardware but also ship unrelated consumer/enterprise devices. A MAC match
     * alone may be a false positive.
     *
     * Note: @NitekryDPaul demoted f8:a2:d6 in July 2026 after it matched a Sony media player.
     * It is kept here because this list is already flagged low-confidence.
     */
    val FLOCK_CONTRACT_MFR_MAC_PREFIXES: Set<String> = setOf(
        "f4:6a:dd", "f8:a2:d6", "e0:0a:f6", "00:f4:8d", "d0:39:57",
        "e8:d0:fc",
    )

    /** SoundThinking (formerly ShotSpotter) - registered to SoundThinking in the IEEE OUI database. */
    val SOUNDTHINKING_MAC_PREFIXES: Set<String> = setOf(
        "d4:11:d6",
    )

    // ------------------------------------------------------------------
    // Method 2: BLE device name patterns (case-insensitive substring)
    // ------------------------------------------------------------------

    val DEVICE_NAME_PATTERNS: List<String> = listOf(
        "FS Ext Battery",
        "Penguin",
        "Flock",
        "Pigvision",
    )

    // ------------------------------------------------------------------
    // Method 3: BLE manufacturer company IDs
    // ------------------------------------------------------------------

    /** XUNTONG - company ID associated with Flock Safety devices (source: wgreenberg/flock-you). */
    const val MFR_ID_XUNTONG: Int = 0x09C8

    val BLE_MANUFACTURER_IDS: Set<Int> = setOf(MFR_ID_XUNTONG)

    // ------------------------------------------------------------------
    // Method 4/5: Raven (SoundThinking/ShotSpotter gunshot detector) GATT service UUIDs
    // ------------------------------------------------------------------

    const val RAVEN_DEVICE_INFO_SERVICE = "0000180a-0000-1000-8000-00805f9b34fb"
    const val RAVEN_GPS_SERVICE = "00003100-0000-1000-8000-00805f9b34fb"
    const val RAVEN_POWER_SERVICE = "00003200-0000-1000-8000-00805f9b34fb"
    const val RAVEN_NETWORK_SERVICE = "00003300-0000-1000-8000-00805f9b34fb"
    const val RAVEN_UPLOAD_SERVICE = "00003400-0000-1000-8000-00805f9b34fb"
    const val RAVEN_ERROR_SERVICE = "00003500-0000-1000-8000-00805f9b34fb"
    const val RAVEN_OLD_HEALTH_SERVICE = "00001809-0000-1000-8000-00805f9b34fb"
    const val RAVEN_OLD_LOCATION_SERVICE = "00001819-0000-1000-8000-00805f9b34fb"

    /** Every UUID that flags a Raven, in the firmware's order. */
    val RAVEN_SERVICE_UUIDS: List<String> = listOf(
        RAVEN_DEVICE_INFO_SERVICE,
        RAVEN_GPS_SERVICE,
        RAVEN_POWER_SERVICE,
        RAVEN_NETWORK_SERVICE,
        RAVEN_UPLOAD_SERVICE,
        RAVEN_ERROR_SERVICE,
        RAVEN_OLD_HEALTH_SERVICE,
        RAVEN_OLD_LOCATION_SERVICE,
    )

    /**
     * Raven UUIDs that are also standard Bluetooth SIG services (Device Information 0x180A,
     * Health Thermometer 0x1809, Location and Navigation 0x1819). Plenty of unrelated devices
     * advertise these, so a match on one of them alone is treated as low confidence.
     */
    val RAVEN_GENERIC_SIG_UUIDS: Set<String> = setOf(
        RAVEN_DEVICE_INFO_SERVICE,
        RAVEN_OLD_HEALTH_SERVICE,
        RAVEN_OLD_LOCATION_SERVICE,
    )

    /** Human-readable label for each Raven service, for the UI. */
    val RAVEN_SERVICE_LABELS: Map<String, String> = mapOf(
        RAVEN_DEVICE_INFO_SERVICE to "Device Info",
        RAVEN_GPS_SERVICE to "GPS",
        RAVEN_POWER_SERVICE to "Power",
        RAVEN_NETWORK_SERVICE to "Network",
        RAVEN_UPLOAD_SERVICE to "Upload",
        RAVEN_ERROR_SERVICE to "Error",
        RAVEN_OLD_HEALTH_SERVICE to "Health (legacy)",
        RAVEN_OLD_LOCATION_SERVICE to "Location (legacy)",
    )
}
