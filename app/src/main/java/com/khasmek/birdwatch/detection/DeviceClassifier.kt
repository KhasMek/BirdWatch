package com.khasmek.birdwatch.detection

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
 * @param serviceData     Service-data payloads (AD type 0x16/0x20/0x21) keyed by UUID in any form.
 *                        Used for Remote ID (UUID 0xFFFA).
 */
data class BleAdvertisement(
    val macAddress: String,
    val deviceName: String? = null,
    val manufacturerIds: Set<Int> = emptySet(),
    val serviceUuids: List<String> = emptyList(),
    val serviceData: Map<String, ByteArray> = emptyMap(),
)

/**
 * Which heuristic fired. `wireName` matches the firmware's `detection_method` JSON strings, for
 * both the BLE-era firmware (first six) and the current WiFi promiscuous firmware (last six,
 * received over USB serial from the ESP32 companion in Phase 4).
 */
enum class DetectionMethod(val wireName: String, val label: String) {
    // Phone BLE (ported from BLE-era firmware)
    MAC_PREFIX("mac_prefix", "MAC OUI"),
    MAC_PREFIX_SOUNDTHINKING("mac_prefix_soundthinking", "SoundThinking OUI"),
    MAC_PREFIX_MFR("mac_prefix_mfr", "Contract-mfr OUI"),
    DEVICE_NAME("device_name", "BLE name"),
    BLE_MFR_ID("ble_mfr_id", "Mfr ID 0x09C8"),
    RAVEN_UUID("raven_uuid", "Raven UUID"),

    // Phone BLE, signature packs (phase 9a)
    BLE_COMPANY_ID("ble_company_id", "BLE company ID"),
    BLE_SERVICE_UUID("ble_service_uuid", "BLE service UUID"),
    BLE_COMPOSITE("ble_composite", "BLE company ID + service"),

    // Phone WiFi access-point scan (phase 9b): the AP's BSSID carries the vendor OUI
    WIFI_AP_OUI("wifi_ap_oui", "WiFi AP OUI"),

    // ASTM F3411 Remote ID broadcast (phase 9c), decoded from BLE service data or a WiFi beacon IE
    REMOTE_ID_BLE("remote_id_ble", "Remote ID (BLE)"),
    REMOTE_ID_WIFI("remote_id_wifi", "Remote ID (WiFi beacon)"),

    // ESP32 WiFi promiscuous firmware, by confidence tier (4 = highest)
    WIFI_WILDCARD_PROBE_IE_SIG("wifi_wildcard_probe_ie_sig", "Probe + IE fingerprint"),
    WIFI_WILDCARD_PROBE("wifi_wildcard_probe", "Wildcard probe"),
    WIFI_OUI_ADDR2("wifi_oui_addr2", "WiFi OUI (transmitter)"),
    WIFI_OUI_ADDR1("wifi_oui_addr1", "WiFi OUI (receiver echo)"),
    WIFI_OUI_ADDR3("wifi_oui_addr3", "WiFi OUI (BSSID echo)"),
    WIFI_SSID("wifi_ssid", "SSID keyword"),
    /** Anything the firmware emits that this app does not know yet. */
    UNKNOWN("unknown", "Unknown");

    companion object {
        fun fromWireName(name: String?): DetectionMethod =
            entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) } ?: UNKNOWN
    }
}

/** What kind of thing was found. Drives counts, colours and export grouping. */
enum class DeviceCategory(val label: String, val shortLabel: String) {
    FLOCK_ALPR("Flock Safety camera", "Flock"),
    GUNSHOT_DETECTOR("Gunshot detector", "Raven"),
    LAW_ENFORCEMENT("Law-enforcement equipment", "LE"),
    WEARABLE_CAMERA("Wearable camera", "Wearable"),
    DRONE("Drone", "Drone"),
}

/**
 * The vendor/product a hit is attributed to. Stored by name in Room, so values may be added
 * but never renamed or removed.
 */
enum class DeviceType(val label: String, val category: DeviceCategory) {
    FLOCK("Flock", DeviceCategory.FLOCK_ALPR),
    SOUNDTHINKING("SoundThinking", DeviceCategory.GUNSHOT_DETECTOR),
    RAVEN("Raven", DeviceCategory.GUNSHOT_DETECTOR),
    AXON("Axon", DeviceCategory.LAW_ENFORCEMENT),
    WATCHGUARD("WatchGuard", DeviceCategory.LAW_ENFORCEMENT),
    DIGITAL_ALLY("Digital Ally", DeviceCategory.LAW_ENFORCEMENT),
    UTILITY_INC("Utility Inc.", DeviceCategory.LAW_ENFORCEMENT),
    META_GLASSES("Meta glasses", DeviceCategory.WEARABLE_CAMERA),
    DJI("DJI", DeviceCategory.DRONE),
    PARROT("Parrot", DeviceCategory.DRONE),
    SKYDIO("Skydio", DeviceCategory.DRONE),
    /** Any UAS broadcasting ASTM F3411 Remote ID, vendor unknown. */
    REMOTE_ID_UAS("Remote ID drone", DeviceCategory.DRONE);

    val isCore: Boolean get() = category == DeviceCategory.FLOCK_ALPR || category == DeviceCategory.GUNSHOT_DETECTOR
}

enum class Confidence { HIGH, LOW }

/**
 * Result of classifying one advertisement.
 *
 * @param matchedOn     The concrete prefix / name pattern / company ID / UUID that triggered.
 * @param ravenFirmware "1.1.x", "1.2.x", "1.3.x" or "?" for Raven hits; null otherwise.
 * @param pack          The opt-in pack that matched, or null for Core (Flock / Raven).
 * @param remoteId      Decoded Remote ID broadcast, for REMOTE_ID_* methods.
 */
data class Classification(
    val method: DetectionMethod,
    val deviceType: DeviceType,
    val confidence: Confidence,
    val matchedOn: String,
    val ravenFirmware: String? = null,
    val pack: PackId? = null,
    val remoteId: RemoteId.Payload? = null,
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
 * then, only if none of those matched, the enabled opt-in [SignaturePacks] in order.
 * The first rule that matches wins. No Android imports here on purpose.
 */
object DeviceClassifier {

    private const val BLUETOOTH_BASE_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    /**
     * Returns a [Classification] if the advertisement looks like a target device, else null.
     * [enabledPacks] selects which opt-in packs are consulted after the Core heuristics.
     */
    fun classify(adv: BleAdvertisement, enabledPacks: Set<PackId> = emptySet()): Classification? {
        classifyCore(adv)?.let { return it }
        if (enabledPacks.isEmpty()) return null
        val uuids = adv.serviceUuids.map(::normalizeUuid).toSet()
        val prefix = macPrefix(adv.macAddress)

        // Remote ID is a protocol, not a vendor signature: any UAS broadcasting it counts, and
        // the payload itself is the evidence. Lives under the Drones pack.
        if (PackId.DRONES in enabledPacks) {
            remoteIdFromBle(adv)?.let { payload ->
                return Classification(
                    method = DetectionMethod.REMOTE_ID_BLE,
                    deviceType = DeviceType.REMOTE_ID_UAS,
                    confidence = Confidence.HIGH,
                    matchedOn = payload.label,
                    pack = PackId.DRONES,
                    remoteId = payload,
                )
            }
        }

        for (pack in SignaturePacks.OPTIONAL) {
            if (pack.id !in enabledPacks) continue
            for (sig in pack.signatures) {
                val hit = match(sig, adv, prefix, uuids) ?: continue
                return hit.copy(pack = pack.id)
            }
        }
        return null
    }

    /** The original firmware's Flock + Raven heuristics only. */
    fun classifyCore(adv: BleAdvertisement): Classification? {
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
            return Classification(DetectionMethod.BLE_MFR_ID, DeviceType.FLOCK, Confidence.HIGH, hex16(id))
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
     * Classify a WiFi access point seen by the phone's own WiFi scan. Only the BSSID's OUI is
     * usable: Core Flock / SoundThinking prefixes first, then WiFi-scoped OUI signatures from the
     * enabled packs. The contract-manufacturer prefixes (Liteon / USI) are deliberately NOT
     * consulted here: they belong to generic WiFi modules found in routers and laptops, so on the
     * AP scan they would flag half a street; docs/SIGNATURES.md scopes them to BLE only. SSID is
     * carried through as the device name but never matched (no vendor has a confirmed fixed SSID
     * pattern yet).
     */
    fun classifyWifiAp(bssid: String, enabledPacks: Set<PackId> = emptySet()): Classification? {
        val prefix = macPrefix(bssid)
        if (prefix in DetectionSignatures.FLOCK_MAC_PREFIXES || prefix in DetectionSignatures.FLOCK_MAC_PREFIXES_2026) {
            return Classification(DetectionMethod.WIFI_AP_OUI, DeviceType.FLOCK, Confidence.HIGH, prefix)
        }
        if (prefix in DetectionSignatures.SOUNDTHINKING_MAC_PREFIXES) {
            return Classification(DetectionMethod.WIFI_AP_OUI, DeviceType.SOUNDTHINKING, Confidence.HIGH, prefix)
        }
        for (pack in SignaturePacks.OPTIONAL) {
            if (pack.id !in enabledPacks) continue
            for (sig in pack.signatures) {
                if (sig is Signature.Oui && Radio.WIFI in sig.radios && sig.prefix == prefix) {
                    return Classification(DetectionMethod.WIFI_AP_OUI, sig.vendor, sig.confidence, sig.prefix, pack = pack.id)
                }
            }
        }
        return null
    }

    /** Decode Remote ID from a WiFi beacon's vendor IE (bytes starting at the OUI). Drones pack only. */
    fun classifyWifiRemoteId(vendorIe: ByteArray?, enabledPacks: Set<PackId>): Classification? {
        if (PackId.DRONES !in enabledPacks) return null
        val payload = RemoteId.parseWifiVendorIe(vendorIe) ?: return null
        return Classification(
            method = DetectionMethod.REMOTE_ID_WIFI,
            deviceType = DeviceType.REMOTE_ID_UAS,
            confidence = Confidence.HIGH,
            matchedOn = payload.label,
            pack = PackId.DRONES,
            remoteId = payload,
        )
    }

    /** The Open Drone ID payload under service UUID 0xFFFA, if this advertisement carries one. */
    fun remoteIdFromBle(adv: BleAdvertisement): RemoteId.Payload? {
        val data = adv.serviceData.entries.firstOrNull { normalizeUuid(it.key) == RemoteId.BLE_SERVICE_UUID }?.value
        return RemoteId.parseBleServiceData(data)
    }

    private fun match(sig: Signature, adv: BleAdvertisement, prefix: String, uuids: Set<String>): Classification? = when (sig) {
        is Signature.Oui ->
            if (Radio.BLE in sig.radios && prefix == sig.prefix) Classification(DetectionMethod.MAC_PREFIX, sig.vendor, sig.confidence, sig.prefix) else null
        is Signature.CompanyId ->
            if (sig.id in adv.manufacturerIds) Classification(DetectionMethod.BLE_COMPANY_ID, sig.vendor, sig.confidence, hex16(sig.id)) else null
        is Signature.ServiceUuid16 ->
            if (normalizeUuid(hex16(sig.uuid)) in uuids) Classification(DetectionMethod.BLE_SERVICE_UUID, sig.vendor, sig.confidence, hex16(sig.uuid)) else null
        is Signature.NameSubstring ->
            if (adv.deviceName?.contains(sig.pattern, ignoreCase = true) == true) Classification(DetectionMethod.DEVICE_NAME, sig.vendor, sig.confidence, sig.pattern) else null
        is Signature.Composite ->
            if (sig.companyId in adv.manufacturerIds && normalizeUuid(hex16(sig.serviceUuid16)) in uuids)
                Classification(DetectionMethod.BLE_COMPOSITE, sig.vendor, sig.confidence, "${hex16(sig.companyId)}+${hex16(sig.serviceUuid16)}")
            else null
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

    /** "0x09C8" style rendering of a 16-bit identifier. */
    fun hex16(v: Int): String = "0x" + v.toString(16).uppercase(Locale.ROOT).padStart(4, '0')

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
