package com.khasmek.flockyou.detection

/**
 * Opt-in detection categories beyond the Core (Flock + Raven) heuristics.
 *
 * Every signature here must trace to a row in `docs/SIGNATURES.md`; the `sources` ids are the
 * S-numbers from that file's Sources table, which is also what the README credits are built
 * from. Pure Kotlin, JVM-tested.
 */

/** A research source we credit. Mirrors `docs/SIGNATURES.md` §5. */
data class Source(val id: String, val name: String, val took: String, val url: String)

object Sources {
    val ALL: List<Source> = listOf(
        Source("S1", "colonelpanichacks / flock-you (BLE-era firmware, commit 6c6930b)",
            "All BLE Flock and Raven signatures, firmware-version estimation, dedupe semantics",
            "https://github.com/colonelpanichacks/flock-you/blob/6c6930b1917383f71a0413e35095c62fe9cae726/src/main.cpp"),
        Source("S2", "OrdoOuroboros / @NitekryDPaul, nite-oui-collection",
            "Flock Cam WiFi OUI list (2026-07-16), receiver-side addr1 technique",
            "https://github.com/nitekry/nite-oui-collection"),
        Source("S3", "DeFlockJoplin / flock-you fork",
            "OUI 82:6b:f2, wildcard-probe + IE-fingerprint signature",
            "https://github.com/DeflockJoplin/flock-you"),
        Source("S4", "Will Greenberg (@wgreenberg) / flock-you fork",
            "XUNTONG company ID 0x09C8",
            "https://github.com/wgreenberg/flock-you"),
        Source("S5", "colonelpanichacks / flock-you (WiFi promiscuous firmware)",
            "ESP32 serial JSON protocol and confidence tiers",
            "https://github.com/colonelpanichacks/flock-you"),
        Source("S6", "colonelpanichacks / oui-spy-unified-blue",
            "Axon preset (OUI, company ID, service UUID); Meta glasses composite matcher and name patterns; drone OUI database",
            "https://github.com/colonelpanichacks/oui-spy-unified-blue"),
        Source("S7", "Bluetooth SIG Assigned Numbers",
            "Company IDs 0x034D, 0x0D53, 0x09C8; 16-bit service UUIDs 0xFC81, 0xFD5F",
            "https://www.bluetooth.com/specifications/assigned-numbers/"),
        Source("S8", "@NitekryDPaul, nite-oui-collection (law-enforcement OUI group)",
            "Axon, WatchGuard, Digital Ally, Utility Inc. OUIs",
            "https://github.com/nitekry/nite-oui-collection/blob/main/groups/le/privacy_invaders_ouis_law_enforcement.csv"),
        Source("S9", "lnxgod / friendorfoe",
            "Cross-reference for the Meta glasses discrimination logic",
            "https://github.com/lnxgod/friendorfoe"),
        Source("S10", "ASTM F3411 / Open Drone ID",
            "Remote ID BLE advertisement format (planned)",
            "https://github.com/opendroneid/opendroneid-core-c"),
        Source("S11", "DeFlock (FoggedLens)",
            "ALPR make/model catalogue; confirms no RF signatures exist for fixed ALPR competitors",
            "https://github.com/FoggedLens/deflock"),
        Source("S12", "IEEE OUI registry",
            "Vendor attribution for every MAC prefix",
            "https://standards-oui.ieee.org/"),
        Source("S13", "Lucia Pintor & Luigi Atzori (2022), IEEE GLOBECOM",
            "\"Analysis of Wi-Fi Probe Requests Towards Information Element Fingerprinting\" (doi:10.1109/GLOBECOM48099.2022.10001618), the IE-fingerprint method used by the ESP32 firmware",
            "https://doi.org/10.1109/GLOBECOM48099.2022.10001618"),
    )

    fun byId(id: String): Source? = ALL.firstOrNull { it.id == id }
}

/** One matchable identifier. Confidence is what a hit records; `sources` cite docs/SIGNATURES.md. */
sealed interface Signature {
    val vendor: DeviceType
    val confidence: Confidence
    val sources: List<String>
    val note: String

    /** First three octets, lowercase "aa:bb:cc". Applies to BLE public addresses (and WiFi in 9b). */
    data class Oui(
        val prefix: String,
        override val vendor: DeviceType,
        override val confidence: Confidence,
        override val sources: List<String>,
        override val note: String = "",
    ) : Signature

    /** Bluetooth SIG company identifier in manufacturer-specific data. */
    data class CompanyId(
        val id: Int,
        override val vendor: DeviceType,
        override val confidence: Confidence,
        override val sources: List<String>,
        override val note: String = "",
    ) : Signature

    /** Bluetooth SIG 16-bit service UUID in the advertised service list. */
    data class ServiceUuid16(
        val uuid: Int,
        override val vendor: DeviceType,
        override val confidence: Confidence,
        override val sources: List<String>,
        override val note: String = "",
    ) : Signature

    /** Case-insensitive substring of the advertised local name. */
    data class NameSubstring(
        val pattern: String,
        override val vendor: DeviceType,
        override val confidence: Confidence,
        override val sources: List<String>,
        override val note: String = "",
    ) : Signature

    /** Company ID AND 16-bit service UUID present in the SAME advertisement. */
    data class Composite(
        val companyId: Int,
        val serviceUuid16: Int,
        override val vendor: DeviceType,
        override val confidence: Confidence,
        override val sources: List<String>,
        override val note: String = "",
    ) : Signature
}

enum class PackId { LAW_ENFORCEMENT, WEARABLE_CAMERAS }

data class SignaturePack(
    val id: PackId,
    val name: String,
    val description: String,
    /** Shown as a BETA tag: signatures are attributed but not yet field-verified on worn/active hardware. */
    val beta: Boolean,
    /** Extra caution text shown under the switch, if any. */
    val caution: String? = null,
    val signatures: List<Signature>,
) {
    val sourceIds: List<String> get() = signatures.flatMap { it.sources }.distinct().sortedBy { it.drop(1).toIntOrNull() ?: 0 }
}

object SignaturePacks {

    private const val AXON = "Axon (Body 3/4, Fleet, Taser 7/10, Signal)"

    val LAW_ENFORCEMENT = SignaturePack(
        id = PackId.LAW_ENFORCEMENT,
        name = "Law-enforcement equipment",
        description = "Axon body cameras, in-car systems and Taser devices over Bluetooth LE.",
        beta = true,
        caution = "Detects police presence (officers, cruisers), not fixed infrastructure. Counted separately from Flock and Raven.",
        signatures = listOf(
            Signature.CompanyId(0x034D, DeviceType.AXON, Confidence.HIGH, listOf("S6", "S7"), "TASER International company ID"),
            Signature.ServiceUuid16(0xFC81, DeviceType.AXON, Confidence.HIGH, listOf("S6", "S7"), "Axon Enterprise service UUID"),
            Signature.Oui("00:25:df", DeviceType.AXON, Confidence.HIGH, listOf("S6", "S8", "S12"), "Axon Enterprise OUI (IEEE)"),
            Signature.Oui("00:1f:55", DeviceType.AXON, Confidence.HIGH, listOf("S8"), AXON),
            Signature.Oui("00:0f:13", DeviceType.AXON, Confidence.HIGH, listOf("S8"), AXON),
            // WatchGuard / Digital Ally / Utility Inc. are WiFi-only OUIs; they arrive with the
            // phone WiFi AP scanner in phase 9b. Listing them here would never match on BLE.
        ),
    )

    val WEARABLE_CAMERAS = SignaturePack(
        id = PackId.WEARABLE_CAMERAS,
        name = "Wearable cameras",
        description = "Meta Ray-Ban and Oakley Meta smart glasses over Bluetooth LE.",
        beta = true,
        caution = null,
        signatures = listOf(
            Signature.Composite(0x0D53, 0xFD5F, DeviceType.META_GLASSES, Confidence.HIGH, listOf("S6", "S7", "S9"),
                "Luxottica company ID + Meta service UUID in the same advertisement"),
            Signature.NameSubstring("Ray-Ban", DeviceType.META_GLASSES, Confidence.HIGH, listOf("S6", "S9")),
            Signature.NameSubstring("Wayfarer", DeviceType.META_GLASSES, Confidence.HIGH, listOf("S6")),
            Signature.NameSubstring("Oakley Meta", DeviceType.META_GLASSES, Confidence.HIGH, listOf("S6")),
            // No OUI on purpose: the glasses use rotating private addresses. Company ID alone or
            // service UUID alone are false-positive magnets (S6 removed them); only the composite counts.
        ),
    )

    /** Every opt-in pack, in evaluation and display order. */
    val OPTIONAL: List<SignaturePack> = listOf(LAW_ENFORCEMENT, WEARABLE_CAMERAS)

    fun byId(id: PackId): SignaturePack = OPTIONAL.first { it.id == id }
}
