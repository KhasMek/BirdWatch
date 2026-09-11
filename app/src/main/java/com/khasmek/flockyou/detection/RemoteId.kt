package com.khasmek.flockyou.detection

/**
 * ASTM F3411 / Open Drone ID ("Remote ID") broadcast decoding. Pure Kotlin, JVM-tested.
 *
 * Wire formats (source S10, opendroneid-core-c):
 *  - **Bluetooth**: AD type 0x16 (Service Data, 16-bit UUID) with UUID `0xFFFA` (ASTM
 *    International). Android hands us the bytes after the UUID: `[0x0D app code][counter][message...]`.
 *    Legacy advertising carries one 25-byte message; BT5 long-range carries a Message Pack.
 *  - **WiFi beacon**: vendor-specific IE (id 221) with OUI `FA:0B:BC` (ASD-STAN), OUI type `0x0D`,
 *    then `[counter][Message Pack]`. Android exposes the IE bytes starting at the OUI.
 *
 * Each message is 25 bytes: byte 0 = (type << 4) | protocol version, then 24 payload bytes.
 * Coordinates are int32 LE * 1e-7; altitudes are uint16 LE * 0.5 - 1000 m (0 = unknown).
 */
object RemoteId {

    const val BLE_SERVICE_UUID_16 = 0xFFFA
    const val BLE_SERVICE_UUID = "0000fffa-0000-1000-8000-00805f9b34fb"
    const val APP_CODE = 0x0D
    val WIFI_VENDOR_OUI = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte())
    const val MESSAGE_SIZE = 25

    /** Seconds between the Unix epoch and 2019-01-01T00:00:00Z, the ODID System-message epoch. */
    const val SYSTEM_EPOCH_OFFSET_S = 1_546_300_800L

    sealed interface Message {
        data class BasicId(val idType: Int, val uaType: Int, val uasId: String) : Message {
            val idTypeLabel: String get() = ID_TYPES.getOrElse(idType) { "ID type $idType" }
            val uaTypeLabel: String get() = UA_TYPES.getOrElse(uaType) { "UA type $uaType" }
        }

        data class Location(
            val status: Int,
            val latitude: Double?,
            val longitude: Double?,
            /** Geodetic (WGS-84) altitude in metres, if reported. */
            val altitudeGeodeticM: Double?,
            val altitudePressureM: Double?,
            /** Height above takeoff (heightAgl=false) or above ground (true), metres. */
            val heightM: Double?,
            val heightAgl: Boolean,
            /** Degrees clockwise from true north, 0-359. */
            val trackDeg: Int?,
            val speedHorizontalMps: Double?,
            val speedVerticalMps: Double?,
            /** Tenths of a second past the hour, UTC. */
            val timestampTenths: Int?,
        ) : Message {
            val statusLabel: String get() = STATUS.getOrElse(status) { "status $status" }
        }

        data class Authentication(val authType: Int) : Message
        data class SelfId(val descriptionType: Int, val description: String) : Message

        data class System(
            val operatorLatitude: Double?,
            val operatorLongitude: Double?,
            val operatorLocationType: Int,
            val areaCount: Int,
            val areaRadiusM: Int,
            val operatorAltitudeM: Double?,
            /** Epoch millis, if the UA reported a timestamp. */
            val timestampEpochMs: Long?,
        ) : Message {
            val operatorLocationLabel: String get() = OPERATOR_LOCATION.getOrElse(operatorLocationType) { "type $operatorLocationType" }
        }

        data class OperatorId(val idType: Int, val operatorId: String) : Message
        data class Unknown(val type: Int) : Message
    }

    /** Everything decoded from one advertisement or beacon. */
    data class Payload(val messages: List<Message>, val counter: Int) {
        val basicId: Message.BasicId? get() = messages.filterIsInstance<Message.BasicId>().firstOrNull { it.uasId.isNotEmpty() }
            ?: messages.filterIsInstance<Message.BasicId>().firstOrNull()
        val location: Message.Location? get() = messages.filterIsInstance<Message.Location>().firstOrNull()
        val system: Message.System? get() = messages.filterIsInstance<Message.System>().firstOrNull()
        val operatorId: Message.OperatorId? get() = messages.filterIsInstance<Message.OperatorId>().firstOrNull()
        val selfId: Message.SelfId? get() = messages.filterIsInstance<Message.SelfId>().firstOrNull()

        /** Best human label: UAS serial / registration, else operator id, else "Remote ID". */
        val label: String
            get() = basicId?.uasId?.takeIf { it.isNotBlank() }
                ?: operatorId?.operatorId?.takeIf { it.isNotBlank() }
                ?: "Remote ID"
    }

    // ------------------------------------------------------------------

    /** Decode the service-data bytes Android returns for UUID 0xFFFA. Null if not Open Drone ID. */
    fun parseBleServiceData(data: ByteArray?): Payload? {
        if (data == null || data.size < 2 + MESSAGE_SIZE) return null
        if ((data[0].toInt() and 0xFF) != APP_CODE) return null
        val counter = data[1].toInt() and 0xFF
        return parseMessages(data, 2, counter)
    }

    /** Decode a WiFi vendor-specific IE payload (starting at the OUI). Null if not Open Drone ID. */
    fun parseWifiVendorIe(ie: ByteArray?): Payload? {
        if (ie == null || ie.size < 5 + MESSAGE_SIZE) return null
        if (ie[0] != WIFI_VENDOR_OUI[0] || ie[1] != WIFI_VENDOR_OUI[1] || ie[2] != WIFI_VENDOR_OUI[2]) return null
        if ((ie[3].toInt() and 0xFF) != APP_CODE) return null
        val counter = ie[4].toInt() and 0xFF
        return parseMessages(ie, 5, counter)
    }

    /** True if [ie] begins with the ASD-STAN OUI + ODID app code (cheap pre-check). */
    fun isWifiRemoteIdIe(ie: ByteArray?): Boolean =
        ie != null && ie.size >= 4 && ie[0] == WIFI_VENDOR_OUI[0] && ie[1] == WIFI_VENDOR_OUI[1] &&
            ie[2] == WIFI_VENDOR_OUI[2] && (ie[3].toInt() and 0xFF) == APP_CODE

    private fun parseMessages(buf: ByteArray, start: Int, counter: Int): Payload? {
        if (buf.size - start < MESSAGE_SIZE) return null
        val type = (buf[start].toInt() and 0xF0) ushr 4
        val messages: List<Message> = if (type == TYPE_PACK) {
            val size = buf[start + 1].toInt() and 0xFF
            val count = buf[start + 2].toInt() and 0xFF
            if (size != MESSAGE_SIZE) return null
            (0 until count).mapNotNull { i ->
                val off = start + 3 + i * MESSAGE_SIZE
                if (off + MESSAGE_SIZE <= buf.size) parseMessage(buf, off) else null
            }
        } else {
            listOf(parseMessage(buf, start))
        }
        return if (messages.isEmpty()) null else Payload(messages, counter)
    }

    /** Decode one 25-byte message at [off]. */
    fun parseMessage(b: ByteArray, off: Int): Message {
        val type = (b[off].toInt() and 0xF0) ushr 4
        return when (type) {
            TYPE_BASIC_ID -> Message.BasicId(
                idType = (b[off + 1].toInt() and 0xF0) ushr 4,
                uaType = b[off + 1].toInt() and 0x0F,
                uasId = ascii(b, off + 2, 20),
            )
            TYPE_LOCATION -> {
                val flags = b[off + 1].toInt() and 0xFF
                val status = (flags and 0xF0) ushr 4
                val heightAgl = flags and 0x04 != 0
                val ewSegment = flags and 0x02 != 0
                val speedMult = flags and 0x01
                val trackRaw = b[off + 2].toInt() and 0xFF
                val speedRaw = b[off + 3].toInt() and 0xFF
                val vSpeedRaw = b[off + 4].toInt()
                Message.Location(
                    status = status,
                    latitude = coord(i32(b, off + 5)),
                    longitude = coord(i32(b, off + 9)),
                    altitudePressureM = alt(u16(b, off + 13)),
                    altitudeGeodeticM = alt(u16(b, off + 15)),
                    heightM = alt(u16(b, off + 17)),
                    heightAgl = heightAgl,
                    trackDeg = if (trackRaw > 179) null else trackRaw + if (ewSegment) 180 else 0,
                    speedHorizontalMps = when {
                        speedRaw == 255 -> null
                        speedMult == 0 -> speedRaw * 0.25
                        else -> speedRaw * 0.75 + 255 * 0.25
                    },
                    speedVerticalMps = if (vSpeedRaw == 63 || vSpeedRaw == -63) null else vSpeedRaw * 0.5,
                    timestampTenths = u16(b, off + 21).let { if (it == 0xFFFF) null else it },
                )
            }
            TYPE_AUTH -> Message.Authentication(authType = (b[off + 1].toInt() and 0xF0) ushr 4)
            TYPE_SELF_ID -> Message.SelfId(
                descriptionType = b[off + 1].toInt() and 0xFF,
                description = ascii(b, off + 2, 23),
            )
            TYPE_SYSTEM -> {
                val flags = b[off + 1].toInt() and 0xFF
                val ts = u32(b, off + 20)
                Message.System(
                    operatorLatitude = coord(i32(b, off + 2)),
                    operatorLongitude = coord(i32(b, off + 6)),
                    operatorLocationType = flags and 0x03,
                    areaCount = u16(b, off + 10),
                    areaRadiusM = (b[off + 12].toInt() and 0xFF) * 10,
                    operatorAltitudeM = alt(u16(b, off + 18)),
                    timestampEpochMs = if (ts == 0L) null else (ts + SYSTEM_EPOCH_OFFSET_S) * 1000,
                )
            }
            TYPE_OPERATOR_ID -> Message.OperatorId(
                idType = b[off + 1].toInt() and 0xFF,
                operatorId = ascii(b, off + 2, 20),
            )
            else -> Message.Unknown(type)
        }
    }

    // ------------------------------------------------------------------

    private const val TYPE_BASIC_ID = 0x0
    private const val TYPE_LOCATION = 0x1
    private const val TYPE_AUTH = 0x2
    private const val TYPE_SELF_ID = 0x3
    private const val TYPE_SYSTEM = 0x4
    private const val TYPE_OPERATOR_ID = 0x5
    private const val TYPE_PACK = 0xF

    val ID_TYPES = listOf("None", "Serial number", "CAA registration", "UTM UUID", "Session ID")
    val UA_TYPES = listOf(
        "Unknown", "Aeroplane", "Helicopter / multirotor", "Gyroplane", "Hybrid lift", "Ornithopter", "Glider",
        "Kite", "Free balloon", "Captive balloon", "Airship", "Parachute", "Rocket", "Tethered aircraft",
        "Ground obstacle", "Other",
    )
    val STATUS = listOf("Undeclared", "On ground", "Airborne", "Emergency", "Remote ID failure")
    val OPERATOR_LOCATION = listOf("Takeoff", "Live GNSS", "Fixed")

    private fun i32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun u16(b: ByteArray, off: Int): Int = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long = i32(b, off).toLong() and 0xFFFF_FFFFL

    private fun coord(raw: Int): Double? = if (raw == 0) null else raw * 1e-7

    private fun alt(raw: Int): Double? = if (raw == 0) null else raw * 0.5 - 1000.0

    private fun ascii(b: ByteArray, off: Int, len: Int): String {
        val end = minOf(off + len, b.size)
        val sb = StringBuilder()
        for (i in off until end) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) break
            sb.append(if (c in 0x20..0x7E) c.toChar() else '?')
        }
        return sb.toString().trim()
    }
}
