package com.khasmek.flockyou.detection

import java.util.Locale

/**
 * Framework-free snapshot of one BLE advertisement, i.e. the subset of an Android `ScanResult`
 * that the heuristics need. Built by [BleScanner]; constructed directly in unit tests.
 *
 * @param macAddress      "AA:BB:CC:DD:EE:FF" in any case.
 * @param deviceName      Advertised local name, or null if the device broadcasts none.
 * @param manufacturerIds Company IDs present in manufacturer-specific data (AD type 0xFF).
 * @param serviceUuids    Advertised service UUIDs. 16-bit ("180a"), 32-bit or full 128-bit forms
 *                        are all accepted and normalised.
 */
data class BleAdvertisement(
    val macAddress: String,
    val deviceName: String? = null,
    val manufacturerIds: Set<Int> = emptySet(),
    val serviceUuids: List<String> = emptyList(),
)

/** Which heuristic fired. `wireName` matches the firmware's `detection_method` JSON strings. */
enum class DetectionMethod(val wireName: String, val label: String) {
    MAC_PREFIX("mac_prefix", "MAC OUI"),
    MAC_PREFIX_SOUNDTHINKING("mac_prefix_soundthinking", "SoundThinking OUI"),
    MAC_PREFIX_MFR("mac_prefix_mfr", "Contract-mfr OUI"),
    DEVICE_NAME("device_name", "BLE name"),
    BLE_MFR_ID("ble_mfr_id", "Mfr ID 0x09C8"),
    RAVEN_UUID("raven_uuid", "Raven UUID"),
}

enum class DeviceType(val label: String) {
    FLOCK("Flock"),
    SOUNDTHINKING("SoundThinking"),
    RAVEN("Raven"),
}

enum class Confidence { HIGH, LOW }

/**
 * Result of classifying one advertisement.
 *
 * @param matchedOn     The concrete prefix / name pattern / company ID / UUID that triggered.
 * @param ravenFirmware "1.1.x", "1.2.x", "1.3.x" or "?" for Raven hits; null otherwise.
 */
data class Classification(
    val method: DetectionMethod,
    val deviceType: DeviceType,
    val confidence: Confidence,
    val matchedOn: String,
    val ravenFirmware: String? = null,
)

/**
 * Pure detection logic. Implements all five heuristics from the original firmware in the same
 * priority order as `FYBLECallbacks::onResult`:
 *
 *  1. Flock Safety direct OUIs (high confidence)
 *  2. SoundThinking / ShotSpotter OUIs (high confidence)
 *  3. Flock contract-manufacturer OUIs (low confidence)
 *  4. BLE device-name substring match
 *  5. BLE manufacturer company ID 0x09C8
 *  6. Raven service-UUID fingerprint, with firmware estimation
 *
 * The first rule that matches wins. No Android imports here on purpose.
 */
object DeviceClassifier {

    private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /** Returns a [Classification] if the advertisement looks like a target device, else null. */
    fun classify(adv: BleAdvertisement): Classification? {
        val prefix = macPrefix(adv.macAddress)

        // 1. Flock Safety direct OUIs
        if (prefix in DetectionSignatures.FLOCK_MAC_PREFIXES ||
            prefix in DetectionSignatures.FLOCK_MAC_PREFIXES_2026
        ) {
            return Classification(DetectionMethod.MAC_PREFIX, DeviceType.FLOCK, Confidence.HIGH, prefix)
        }

        // 2. SoundThinking / ShotSpotter OUIs
        if (prefix in DetectionSignatures.SOUNDTHINKING_MAC_PREFIXES) {
            return Classification(
                DetectionMethod.MAC_PREFIX_SOUNDTHINKING, DeviceType.SOUNDTHINKING, Confidence.HIGH, prefix
            )
        }

        // 3. Flock contract manufacturer OUIs (Liteon / USI) - low confidence alone
        if (prefix in DetectionSignatures.FLOCK_CONTRACT_MFR_MAC_PREFIXES) {
            return Classification(DetectionMethod.MAC_PREFIX_MFR, DeviceType.FLOCK, Confidence.LOW, prefix)
        }

        // 4. Device name patterns (case-insensitive substring)
        matchDeviceName(adv.deviceName)?.let { pattern ->
            return Classification(DetectionMethod.DEVICE_NAME, DeviceType.FLOCK, Confidence.HIGH, pattern)
        }

        // 5. Manufacturer company ID
        adv.manufacturerIds.firstOrNull { it in DetectionSignatures.BLE_MANUFACTURER_IDS }?.let { id ->
            return Classification(
                DetectionMethod.BLE_MFR_ID, DeviceType.FLOCK, Confidence.HIGH,
                "0x" + id.toString(16).uppercase(Locale.ROOT).padStart(4, '0')
            )
        }

        // 6. Raven service UUIDs
        val uuids = adv.serviceUuids.map(::normalizeUuid)
        val ravenHit = DetectionSignatures.RAVEN_SERVICE_UUIDS.firstOrNull { it in uuids }
        if (ravenHit != null) {
            val hasCustomRavenService = uuids.any {
                it in DetectionSignatures.RAVEN_SERVICE_UUIDS && it !in DetectionSignatures.RAVEN_GENERIC_SIG_UUIDS
            }
            return Classification(
                method = DetectionMethod.RAVEN_UUID,
                deviceType = DeviceType.RAVEN,
                confidence = if (hasCustomRavenService) Confidence.HIGH else Confidence.LOW,
                matchedOn = ravenHit,
                ravenFirmware = estimateRavenFirmware(uuids),
            )
        }

        return null
    }

    /**
     * Firmware estimation from the advertised service set, verbatim from `estimateRavenFW()`:
     *  - legacy Location (0x1819) present, new GPS (0x3100) absent  -> "1.1.x"
     *  - new GPS present, Power (0x3200) absent                     -> "1.2.x"
     *  - new GPS present, Power present                             -> "1.3.x"
     *  - anything else                                              -> "?"
     */
    fun estimateRavenFirmware(serviceUuids: Collection<String>): String {
        val u = serviceUuids.map(::normalizeUuid).toSet()
        val hasNewGps = DetectionSignatures.RAVEN_GPS_SERVICE in u
        val hasOldLoc = DetectionSignatures.RAVEN_OLD_LOCATION_SERVICE in u
        val hasPower = DetectionSignatures.RAVEN_POWER_SERVICE in u
        return when {
            hasOldLoc && !hasNewGps -> "1.1.x"
            hasNewGps && !hasPower -> "1.2.x"
            hasNewGps && hasPower -> "1.3.x"
            else -> "?"
        }
    }

    /** First three octets, lowercase, e.g. "58:8e:81". */
    fun macPrefix(mac: String): String = mac.trim().lowercase(Locale.ROOT).take(8)

    /** Returns the first name pattern contained (case-insensitively) in [name], or null. */
    fun matchDeviceName(name: String?): String? {
        if (name.isNullOrEmpty()) return null
        return DetectionSignatures.DEVICE_NAME_PATTERNS.firstOrNull { name.contains(it, ignoreCase = true) }
    }

    /**
     * Normalise any UUID spelling to lowercase 128-bit canonical form. Short 16-bit ("180a" or
     * "0x180A") and 32-bit forms are expanded with the Bluetooth base UUID, matching what Android's
     * `ParcelUuid.toString()` produces for advertised short UUIDs.
     */
    fun normalizeUuid(raw: String): String {
        val s = raw.trim().lowercase(Locale.ROOT).removePrefix("0x")
        return when (s.length) {
            4 -> "0000$s$BLUETOOTH_BASE_SUFFIX"
            8 -> "$s$BLUETOOTH_BASE_SUFFIX"
            else -> s
        }
    }
}
