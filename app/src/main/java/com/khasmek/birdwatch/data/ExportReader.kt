package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionMethod
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DetectionTable
import com.khasmek.birdwatch.detection.DeviceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * A session reconstructed from one of BirdWatch's own export files, plus any per-device edits
 * (alias, moved pin, hidden) the file carried. Devices hold the *detected* positions; the
 * corrections live in [overrides], exactly as in the database.
 */
data class ImportedSession(
    val session: ScanSession,
    val devices: List<DetectedDevice>,
    val format: ExportFormat,
    val overrides: List<DeviceOverride> = emptyList(),
    /** Signal-trail breadcrumbs (JSON only), already stamped with this session's id. */
    val samples: List<SightingSample> = emptyList(),
)

class ImportFormatException(message: String) : Exception(message)

/**
 * Reads the JSON and CSV files written by [ExportWriter] back into a session. Pure Kotlin.
 *
 * The original session id is preserved so the same file cannot be imported twice by accident
 * (the caller checks for an existing id). KML is deliberately not readable: it only carries
 * located devices and a lossy description block.
 */
object ExportReader {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Sniff the content and dispatch. Throws [ImportFormatException] with a user-readable reason. */
    fun parse(text: String, fileName: String? = null): ImportedSession {
        // Strip a leading UTF-8 BOM (some editors add one) and whitespace before sniffing.
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            trimmed.startsWith("{") -> parseJson(trimmed)
            trimmed.startsWith("<") -> throw ImportFormatException("KML files can't be imported; use the JSON or CSV export of the same session")
            trimmed.startsWith(ExportWriter.CSV_HEADER.first()) -> parseCsv(trimmed)
            else -> throw ImportFormatException(
                "Not a BirdWatch export" + (fileName?.let { " ($it)" } ?: "") + "; expected the app's JSON or CSV format"
            )
        }
    }

    fun parseJson(text: String): ImportedSession {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ImportFormatException("File is not valid JSON")
        return parseJson(root)
    }

    /** Parse an already-decoded export document (`{"session": {...}, "devices": [...]}`). */
    fun parseJson(root: JsonObject): ImportedSession {
        val sessionObj = root["session"] as? JsonObject ?: throw ImportFormatException("JSON has no \"session\" object; not a BirdWatch export")
        val id = sessionObj.str("id")?.takeIf { it.isNotBlank() } ?: throw ImportFormatException("Session has no id")
        val startedAt = sessionObj.str("started_at")?.let(::epoch) ?: throw ImportFormatException("Session has no started_at")
        val endedAt = sessionObj.str("ended_at")?.let(::epoch)
        val label = sessionObj.str("label")
        val devicesArr = root["devices"] as? JsonArray ?: JsonArray(emptyList())

        val overrides = mutableListOf<DeviceOverride>()
        val samples = mutableListOf<SightingSample>()
        val devices = devicesArr.mapIndexedNotNull { i, el ->
            val d = el as? JsonObject ?: return@mapIndexedNotNull null
            val mac = d.str("mac_address")?.takeIf { it.isNotBlank() }
                ?: throw ImportFormatException("Device #${i + 1} has no mac_address")
            val rid = d["remote_id"] as? JsonObject
            val normalizedMac = DetectionTable.normalizeMac(mac)

            // Signal trail rows: [time, latitude, longitude, rssi]; a malformed row is skipped.
            (d["trail"] as? JsonArray)?.forEach { row ->
                val a = row as? JsonArray ?: return@forEach
                if (a.size < 4) return@forEach
                val t = (a[0] as? JsonPrimitive)?.contentOrNull?.let { runCatching { epoch(it) }.getOrNull() } ?: return@forEach
                val lat = (a[1] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                val lon = (a[2] as? JsonPrimitive)?.doubleOrNull ?: return@forEach
                val rssi = (a[3] as? JsonPrimitive)?.intOrNull ?: return@forEach
                samples += SightingSample(sessionId = id, macAddress = normalizedMac, time = t, latitude = lat, longitude = lon, rssi = rssi)
            }

            // User edits: the file's latitude/longitude are the corrected position when
            // position_edited; the detected fix is under user_edit. Undo that here.
            val edit = d["user_edit"] as? JsonObject
            val positionEdited = edit?.let { (it["position_edited"] as? JsonPrimitive)?.booleanOrNull } == true
            val alias = d.str("alias")
            val detectedLat = if (positionEdited) edit?.dbl("detected_latitude") else d.dbl("latitude")
            val detectedLon = if (positionEdited) edit?.dbl("detected_longitude") else d.dbl("longitude")
            val override = DeviceOverride(
                macAddress = normalizedMac,
                latitude = if (positionEdited) d.dbl("latitude") else null,
                longitude = if (positionEdited) d.dbl("longitude") else null,
                alias = alias,
                hidden = edit?.let { (it["hidden"] as? JsonPrimitive)?.booleanOrNull } == true,
                updatedAt = edit?.str("updated_at")?.let { runCatching { epoch(it) }.getOrDefault(0L) } ?: 0L,
                notes = d.str("notes"),
                track = edit?.let { (it["track"] as? JsonPrimitive)?.booleanOrNull } == true,
            )
            if (!override.isEmpty) overrides += override

            DetectedDevice(
                sessionId = id,
                macAddress = normalizedMac,
                source = enumOr(d.str("source"), DetectionSource.BLE),
                deviceName = d.str("device_name"),
                detectionMethod = DetectionMethod.fromWireName(d.str("detection_method")),
                deviceType = enumOr(d.str("device_type"), DeviceType.FLOCK),
                confidence = enumOr(d.str("confidence"), Confidence.LOW),
                matchedOn = d.str("matched_on") ?: "",
                ravenFirmware = d.str("raven_fw"),
                tier = d.int("detection_tier"),
                channel = d.int("channel"),
                rssi = d.int("rssi") ?: 0,
                latitude = detectedLat,
                longitude = detectedLon,
                accuracyMeters = d.flt("gps_accuracy_m"),
                firstSeen = d.str("first_seen")?.let(::epoch) ?: startedAt,
                lastSeen = d.str("last_seen")?.let(::epoch) ?: startedAt,
                sightings = (d.int("sightings") ?: 1).coerceAtLeast(1),
                uasId = rid?.str("uas_id"),
                operatorId = rid?.str("operator_id"),
                targetLatitude = rid?.dbl("target_latitude"),
                targetLongitude = rid?.dbl("target_longitude"),
                targetAltitudeM = rid?.dbl("target_altitude_m"),
                operatorLatitude = rid?.dbl("operator_latitude"),
                operatorLongitude = rid?.dbl("operator_longitude"),
            )
        }.distinctBy { it.macAddress }

        return ImportedSession(
            session = ScanSession(
                id = id,
                startedAt = startedAt,
                endedAt = endedAt ?: (devices.maxOfOrNull { it.lastSeen } ?: startedAt),
                label = label,
            ),
            devices = devices,
            format = ExportFormat.JSON,
            overrides = overrides.distinctBy { it.macAddress },
            samples = samples,
        )
    }

    fun parseCsv(text: String): ImportedSession {
        val records = parseCsvRecords(text)
        if (records.isEmpty()) throw ImportFormatException("CSV file is empty")
        return parseCsvRows(records.first(), records.drop(1))
    }

    /** Build a session from a header record and its data records (all of one session id). */
    fun parseCsvRows(header: List<String>, rows: List<List<String>>): ImportedSession {
        val col = header.withIndex().associate { (i, name) -> name to i }
        val required = listOf("session_id", "mac_address", "detection_method", "device_type", "rssi", "first_seen", "last_seen")
        required.firstOrNull { it !in col }?.let { throw ImportFormatException("CSV is missing the \"$it\" column; not a BirdWatch export") }

        fun row(cells: List<String>, name: String): String? = col[name]?.let { cells.getOrNull(it) }?.takeIf { it.isNotEmpty() }

        if (rows.isEmpty()) throw ImportFormatException("CSV has a header but no rows")
        val sessionIds = rows.mapNotNull { row(it, "session_id") }.distinct()
        if (sessionIds.size != 1) throw ImportFormatException("CSV must contain exactly one session (found ${sessionIds.size})")
        val id = sessionIds.single()

        val overrides = mutableListOf<DeviceOverride>()
        val devices = rows.mapIndexed { i, c ->
            val mac = row(c, "mac_address") ?: throw ImportFormatException("Row ${i + 2} has no mac_address")
            val normalizedMac = DetectionTable.normalizeMac(mac)

            // User edits (see ExportWriter.CSV_HEADER): latitude/longitude are the correction when
            // position_edited is set, and the detected fix is in detected_*.
            val positionEdited = row(c, "position_edited").equals("true", ignoreCase = true)
            val override = DeviceOverride(
                macAddress = normalizedMac,
                latitude = if (positionEdited) row(c, "latitude")?.toDoubleOrNull() else null,
                longitude = if (positionEdited) row(c, "longitude")?.toDoubleOrNull() else null,
                alias = row(c, "alias"),
                hidden = row(c, "hidden").equals("true", ignoreCase = true),
                updatedAt = row(c, "edited_at")?.let { runCatching { epoch(it) }.getOrDefault(0L) } ?: 0L,
                notes = row(c, "notes"),
                track = row(c, "track").equals("true", ignoreCase = true),
            )
            if (!override.isEmpty) overrides += override

            DetectedDevice(
                sessionId = id,
                macAddress = normalizedMac,
                source = enumOr(row(c, "source"), DetectionSource.BLE),
                deviceName = row(c, "device_name"),
                detectionMethod = DetectionMethod.fromWireName(row(c, "detection_method")),
                deviceType = enumOr(row(c, "device_type"), DeviceType.FLOCK),
                confidence = enumOr(row(c, "confidence"), Confidence.LOW),
                matchedOn = row(c, "matched_on") ?: "",
                ravenFirmware = row(c, "raven_fw"),
                tier = row(c, "detection_tier")?.toIntOrNull(),
                channel = row(c, "channel")?.toIntOrNull(),
                rssi = row(c, "rssi")?.toIntOrNull() ?: 0,
                latitude = row(c, if (positionEdited) "detected_latitude" else "latitude")?.toDoubleOrNull(),
                longitude = row(c, if (positionEdited) "detected_longitude" else "longitude")?.toDoubleOrNull(),
                accuracyMeters = row(c, "gps_accuracy_m")?.toFloatOrNull(),
                firstSeen = row(c, "first_seen")?.let(::epoch) ?: throw ImportFormatException("Row ${i + 2} has no first_seen"),
                lastSeen = row(c, "last_seen")?.let(::epoch) ?: throw ImportFormatException("Row ${i + 2} has no last_seen"),
                sightings = (row(c, "sightings")?.toIntOrNull() ?: 1).coerceAtLeast(1),
                uasId = row(c, "uas_id"),
                operatorId = row(c, "operator_id"),
                targetLatitude = row(c, "target_latitude")?.toDoubleOrNull(),
                targetLongitude = row(c, "target_longitude")?.toDoubleOrNull(),
                targetAltitudeM = row(c, "target_altitude_m")?.toDoubleOrNull(),
                operatorLatitude = row(c, "operator_latitude")?.toDoubleOrNull(),
                operatorLongitude = row(c, "operator_longitude")?.toDoubleOrNull(),
            )
        }.distinctBy { it.macAddress }

        // CSV carries no session header: bound the session by its detections.
        return ImportedSession(
            session = ScanSession(id = id, startedAt = devices.minOf { it.firstSeen }, endedAt = devices.maxOf { it.lastSeen }),
            devices = devices,
            format = ExportFormat.CSV,
            overrides = overrides.distinctBy { it.macAddress },
        )
    }

    // ------------------------------------------------------------------

    /**
     * RFC 4180 tokenizer over the whole text: quoted cells may contain commas, doubled quotes and
     * line breaks (a BLE name or SSID can carry a newline, and [ExportWriter.csvCell] quotes
     * it). Records are split on LF, CR or CRLF outside quotes; blank records are skipped.
     */
    fun parseCsvRecords(text: String): List<List<String>> {
        val records = mutableListOf<List<String>>()
        var cells = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false

        fun endRecord() {
            cells += sb.toString()
            sb.clear()
            if (cells.size > 1 || cells[0].isNotBlank()) records += cells
            cells = mutableListOf()
        }

        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { cells += sb.toString(); sb.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRecord()
                }
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || cells.isNotEmpty()) endRecord() // last record without a trailing newline
        return records
    }

    private fun epoch(iso: String): Long = try {
        Instant.parse(iso.trim()).toEpochMilli()
    } catch (e: DateTimeParseException) {
        throw ImportFormatException("Bad timestamp \"$iso\"")
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        enumValues<E>().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: fallback

    private fun JsonObject.prim(key: String): JsonPrimitive? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }
    private fun JsonObject.str(key: String): String? = prim(key)?.contentOrNull?.takeIf { it.isNotEmpty() }
    private fun JsonObject.int(key: String): Int? = prim(key)?.intOrNull
    private fun JsonObject.dbl(key: String): Double? = prim(key)?.doubleOrNull
    private fun JsonObject.flt(key: String): Float? = prim(key)?.floatOrNull
}
