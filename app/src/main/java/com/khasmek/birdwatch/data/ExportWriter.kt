package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String, val label: String) {
    JSON("json", "application/json", "JSON"),
    CSV("csv", "text/csv", "CSV"),
    KML("kml", "application/vnd.google-earth.kml+xml", "KML (Google Earth)"),
}

/** Per-MAC user edits to apply while exporting, keyed by normalised MAC. */
typealias Overrides = Map<String, DeviceOverride>

/** Signal-trail breadcrumbs of one session, keyed by normalised MAC (JSON only). */
typealias Trails = Map<String, List<SightingSample>>

/**
 * Pure serialisers for a session's detections. No Android dependencies; JVM-tested.
 * Field names follow the upstream Flask dashboard exports where they overlap
 * (`mac_address`, `detection_method`, `detection_tier`, `rssi`, `latitude`, `longitude`).
 *
 * User edits ([DeviceOverride]) are applied on the way out: `latitude`/`longitude` are the
 * corrected position when the user moved the pin (the detected one travels alongside as
 * `detected_latitude`/`detected_longitude`), and `alias` is added when set, so anything that
 * reads the file sees the map as the user sees it. [ExportReader] undoes this on import.
 *
 * The per-device pieces ([deviceObject], [csvRow], [appendKmlPlacemarks]) are shared with
 * [BackupWriter], so a backup is exactly "several sessions in the export format".
 */
object ExportWriter {

    internal val json = Json { prettyPrint = true }

    fun write(
        format: ExportFormat,
        session: ScanSession,
        devices: List<DetectedDevice>,
        exportedAt: Long = System.currentTimeMillis(),
        overrides: Overrides = emptyMap(),
        trails: Trails = emptyMap(),
    ): String = when (format) {
        ExportFormat.JSON -> json(session, devices, exportedAt, overrides, trails)
        ExportFormat.CSV -> csv(devices, overrides)
        ExportFormat.KML -> kml(session, devices, overrides)
    }

    /** The position a consumer should use: the user's correction if there is one, else the detected fix. */
    fun effectiveLatitude(d: DetectedDevice, o: DeviceOverride?): Double? = if (o?.hasLocation == true) o.latitude else d.latitude
    fun effectiveLongitude(d: DetectedDevice, o: DeviceOverride?): Double? = if (o?.hasLocation == true) o.longitude else d.longitude

    fun fileName(format: ExportFormat, session: ScanSession): String =
        "birdwatch_${isoCompact(session.startedAt)}_${session.id.take(8)}.${format.extension}"

    // ------------------------------------------------------------------
    // JSON
    // ------------------------------------------------------------------

    fun json(
        session: ScanSession,
        devices: List<DetectedDevice>,
        exportedAt: Long,
        overrides: Overrides = emptyMap(),
        trails: Trails = emptyMap(),
    ): String {
        val root = buildJsonObject {
            put("app", "birdwatch")
            put("exported_at", iso(exportedAt))
            put("session", sessionObject(session, devices.size))
            put("devices", buildJsonArray { devices.forEach { add(deviceObject(it, overrides[it.macAddress], trails[it.macAddress])) } })
        }
        return json.encodeToString(JsonObject.serializer(), root)
    }

    fun sessionObject(session: ScanSession, deviceCount: Int): JsonObject = buildJsonObject {
        put("id", session.id)
        put("started_at", iso(session.startedAt))
        put("ended_at", session.endedAt?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
        put("label", session.label?.let { JsonPrimitive(it) } ?: JsonNull)
        put("device_count", deviceCount)
    }

    fun deviceObject(d: DetectedDevice, o: DeviceOverride? = null, trail: List<SightingSample>? = null): JsonObject = buildJsonObject {
        put("mac_address", d.macAddress)
        put("device_name", d.deviceName?.let { JsonPrimitive(it) } ?: JsonNull)
        o?.alias?.takeIf { it.isNotBlank() }?.let { put("alias", it) }
        o?.notes?.takeIf { it.isNotBlank() }?.let { put("notes", it) }
        put("source", d.source.name)
        put("detection_method", d.detectionMethod.wireName)
        put("device_type", d.deviceType.name)
        put("category", d.deviceType.category.name)
        put("confidence", d.confidence.name)
        put("matched_on", d.matchedOn)
        put("raven_fw", d.ravenFirmware?.let { JsonPrimitive(it) } ?: JsonNull)
        put("detection_tier", d.tier?.let { JsonPrimitive(it) } ?: JsonNull)
        put("channel", d.channel?.let { JsonPrimitive(it) } ?: JsonNull)
        put("rssi", d.rssi)
        put("latitude", effectiveLatitude(d, o)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("longitude", effectiveLongitude(d, o)?.let { JsonPrimitive(it) } ?: JsonNull)
        put("gps_accuracy_m", d.accuracyMeters?.let { JsonPrimitive(it) } ?: JsonNull)
        put("first_seen", iso(d.firstSeen))
        put("last_seen", iso(d.lastSeen))
        put("sightings", d.sightings)
        if (o != null && !o.isEmpty) {
            put("user_edit", buildJsonObject {
                put("position_edited", o.hasLocation)
                put("detected_latitude", d.latitude?.let { JsonPrimitive(it) } ?: JsonNull)
                put("detected_longitude", d.longitude?.let { JsonPrimitive(it) } ?: JsonNull)
                put("hidden", o.hidden)
                put("track", o.track)
                put("updated_at", if (o.updatedAt > 0) JsonPrimitive(iso(o.updatedAt)) else JsonNull)
            })
        }
        // Signal trail as compact rows: [time, latitude, longitude, rssi]. ("sightings" above is
        // the count; this must not reuse that key.)
        if (!trail.isNullOrEmpty()) {
            put("trail", buildJsonArray {
                trail.forEach { s ->
                    add(buildJsonArray { add(JsonPrimitive(iso(s.time))); add(JsonPrimitive(s.latitude)); add(JsonPrimitive(s.longitude)); add(JsonPrimitive(s.rssi)) })
                }
            })
        }
        if (d.isRemoteId) {
            put("remote_id", buildJsonObject {
                put("uas_id", d.uasId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("operator_id", d.operatorId?.let { JsonPrimitive(it) } ?: JsonNull)
                put("target_latitude", d.targetLatitude?.let { JsonPrimitive(it) } ?: JsonNull)
                put("target_longitude", d.targetLongitude?.let { JsonPrimitive(it) } ?: JsonNull)
                put("target_altitude_m", d.targetAltitudeM?.let { JsonPrimitive(it) } ?: JsonNull)
                put("operator_latitude", d.operatorLatitude?.let { JsonPrimitive(it) } ?: JsonNull)
                put("operator_longitude", d.operatorLongitude?.let { JsonPrimitive(it) } ?: JsonNull)
            })
        }
    }

    // ------------------------------------------------------------------
    // CSV
    // ------------------------------------------------------------------

    val CSV_HEADER = listOf(
        "session_id", "mac_address", "device_name", "source", "detection_method", "device_type", "category",
        "confidence", "matched_on", "raven_fw", "detection_tier", "channel", "rssi",
        "latitude", "longitude", "gps_accuracy_m", "first_seen", "last_seen", "sightings",
        "uas_id", "operator_id", "target_latitude", "target_longitude", "target_altitude_m",
        "operator_latitude", "operator_longitude",
        // user edits (v4): alias, whether latitude/longitude above is the user's correction, the
        // detected fix when it is, hidden flag, when the edit was made
        "alias", "position_edited", "detected_latitude", "detected_longitude", "hidden", "edited_at", "notes", "track",
    )

    fun csv(devices: List<DetectedDevice>, overrides: Overrides = emptyMap()): String = buildString {
        appendLine(CSV_HEADER.joinToString(","))
        devices.forEach { appendLine(csvRow(it, overrides[it.macAddress])) }
    }

    fun csvRow(d: DetectedDevice, o: DeviceOverride? = null): String = listOf(
        d.sessionId, d.macAddress, d.deviceName ?: "", d.source.name, d.detectionMethod.wireName,
        d.deviceType.name, d.deviceType.category.name, d.confidence.name, d.matchedOn, d.ravenFirmware ?: "",
        d.tier?.toString() ?: "", d.channel?.toString() ?: "", d.rssi.toString(),
        effectiveLatitude(d, o)?.let { fmt(it) } ?: "", effectiveLongitude(d, o)?.let { fmt(it) } ?: "",
        d.accuracyMeters?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
        iso(d.firstSeen), iso(d.lastSeen), d.sightings.toString(),
        d.uasId ?: "", d.operatorId ?: "",
        d.targetLatitude?.let { fmt(it) } ?: "", d.targetLongitude?.let { fmt(it) } ?: "",
        d.targetAltitudeM?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
        d.operatorLatitude?.let { fmt(it) } ?: "", d.operatorLongitude?.let { fmt(it) } ?: "",
        o?.alias ?: "",
        if (o?.hasLocation == true) "true" else "",
        if (o?.hasLocation == true) d.latitude?.let { fmt(it) } ?: "" else "",
        if (o?.hasLocation == true) d.longitude?.let { fmt(it) } ?: "" else "",
        if (o?.hidden == true) "true" else "",
        if (o != null && !o.isEmpty && o.updatedAt > 0) iso(o.updatedAt) else "",
        o?.notes ?: "",
        if (o?.track == true) "true" else "",
    ).joinToString(",") { csvCell(it) }

    // ------------------------------------------------------------------
    // KML
    // ------------------------------------------------------------------

    fun kml(session: ScanSession, devices: List<DetectedDevice>, overrides: Overrides = emptyMap()): String = buildString {
        val located = devices.filter { it.hasLocation || it.hasTargetLocation || overrides[it.macAddress]?.hasLocation == true }
        appendLine(KML_HEAD)
        appendLine("<Document>")
        appendLine("  <name>${xml("BirdWatch session " + isoCompact(session.startedAt))}</name>")
        appendLine("  <description>${xml("${devices.size} devices, ${located.size} with GPS. Session ${session.id}")}</description>")
        appendKmlStyles(this)
        appendKmlPlacemarks(this, devices, indent = "  ", overrides = overrides)
        appendLine("</Document>")
        appendLine("</kml>")
    }

    const val KML_HEAD = """<?xml version="1.0" encoding="UTF-8"?>
<kml xmlns="http://www.opengis.net/kml/2.2">"""

    /** KML style id -> (aabbggrr colour, Google paddle icon name). */
    private val KML_STYLES: Map<String, Pair<String, String>> = mapOf(
        "flock" to ("ff0051e6" to "orange-circle"),
        "raven" to ("ff9a1b6a" to "purple-circle"),
        "le" to ("ffc06515" to "blu-circle"),
        "wearable" to ("ff7b8900" to "ltblu-circle"),
        "drone" to ("ff7a6e54" to "ylw-circle"),
    )

    fun appendKmlStyles(sb: StringBuilder) {
        KML_STYLES.forEach { (id, style) -> sb.appendLine("  <Style id=\"$id\"><IconStyle><color>${style.first}</color><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/${style.second}.png</href></Icon></IconStyle></Style>") }
        sb.appendLine("""  <Style id="operator"><IconStyle><color>ff8f40e9</color><scale>1.0</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/pink-circle.png</href></Icon></IconStyle></Style>""")
        sb.appendLine("""  <Style id="operator-link"><LineStyle><color>b37a6e54</color><width>3</width></LineStyle></Style>""")
    }

    /**
     * Placemarks for every located device (drones at their reported position, user-moved pins at
     * the corrected one) plus operator markers and links. Devices the user hid are left out.
     */
    fun appendKmlPlacemarks(sb: StringBuilder, devices: List<DetectedDevice>, indent: String, overrides: Overrides = emptyMap()) {
        val shown = devices.filter { overrides[it.macAddress]?.hidden != true }
        val located = shown.filter { it.hasLocation || it.hasTargetLocation || overrides[it.macAddress]?.hasLocation == true }
        located.forEach { d ->
            val o = overrides[d.macAddress]
            val alias = o?.alias?.takeIf { it.isNotBlank() }
            sb.appendLine("$indent<Placemark>")
            sb.appendLine("$indent  <name>${xml("${d.deviceType.label}: ${alias ?: d.displayName}")}</name>")
            sb.appendLine("$indent  <styleUrl>#${kmlStyleId(d.deviceType.category)}</styleUrl>")
            sb.appendLine("$indent  <TimeStamp><when>${iso(d.lastSeen)}</when></TimeStamp>")
            sb.appendLine("$indent  <description><![CDATA[")
            if (alias != null) sb.appendLine("$indent    <b>Detected name:</b> ${xml(d.displayName)}<br/>")
            o?.notes?.takeIf { it.isNotBlank() }?.let { sb.appendLine("$indent    <b>Notes:</b> ${xml(it)}<br/>") }
            sb.appendLine("$indent    <b>MAC:</b> ${d.macAddress}<br/>")
            sb.appendLine("$indent    <b>Category:</b> ${d.deviceType.category.label}<br/>")
            sb.appendLine("$indent    <b>Source:</b> ${d.source.label}<br/>")
            sb.appendLine("$indent    <b>Method:</b> ${d.detectionMethod.label} (${d.matchedOn})<br/>")
            sb.appendLine("$indent    <b>Confidence:</b> ${d.confidence.name}<br/>")
            d.tier?.let { sb.appendLine("$indent    <b>Tier:</b> $it<br/>") }
            d.channel?.let { sb.appendLine("$indent    <b>Channel:</b> $it<br/>") }
            d.ravenFirmware?.let { sb.appendLine("$indent    <b>Raven FW:</b> $it<br/>") }
            sb.appendLine("$indent    <b>RSSI:</b> ${d.rssi} dBm<br/>")
            sb.appendLine("$indent    <b>Sightings:</b> ${d.sightings}<br/>")
            sb.appendLine("$indent    <b>First seen:</b> ${iso(d.firstSeen)}<br/>")
            sb.appendLine("$indent    <b>Last seen:</b> ${iso(d.lastSeen)}<br/>")
            d.accuracyMeters?.let { sb.appendLine("$indent    <b>GPS accuracy:</b> ${String.format(Locale.ROOT, "%.0f", it)} m<br/>") }
            if (d.isRemoteId) {
                d.uasId?.let { sb.appendLine("$indent    <b>UAS ID:</b> ${xml(it)}<br/>") }
                d.operatorId?.let { sb.appendLine("$indent    <b>Operator ID:</b> ${xml(it)}<br/>") }
                if (d.hasOperatorLocation) sb.appendLine("$indent    <b>Operator position:</b> ${fmt(d.operatorLatitude!!)}, ${fmt(d.operatorLongitude!!)}<br/>")
                if (d.hasTargetLocation) sb.appendLine("$indent    <i>Placemark is the drone's self-reported position.</i><br/>")
            }
            if (o?.hasLocation == true) {
                sb.appendLine("$indent    <i>Placemark position set by the user.</i>" +
                    (if (d.hasLocation) " Detected at ${fmt(d.latitude!!)}, ${fmt(d.longitude!!)}." else "") + "<br/>")
            }
            sb.appendLine("$indent  ]]></description>")
            when {
                o?.hasLocation == true ->
                    sb.appendLine("$indent  <Point><coordinates>${fmt(o.longitude!!)},${fmt(o.latitude!!)},0</coordinates></Point>")
                d.hasTargetLocation ->
                    sb.appendLine("$indent  <Point><altitudeMode>absolute</altitudeMode><coordinates>${fmt(d.targetLongitude!!)},${fmt(d.targetLatitude!!)},${String.format(Locale.ROOT, "%.0f", d.targetAltitudeM ?: 0.0)}</coordinates></Point>")
                else ->
                    sb.appendLine("$indent  <Point><coordinates>${fmt(d.longitude!!)},${fmt(d.latitude!!)},0</coordinates></Point>")
            }
            sb.appendLine("$indent</Placemark>")
        }
        // Remote ID operators: their own placemark plus a line back to the aircraft.
        shown.filter { it.hasOperatorLocation }.forEach { d ->
            val label = xml(d.uasId ?: d.displayName)
            sb.appendLine("$indent<Placemark>")
            sb.appendLine("$indent  <name>Operator: $label</name>")
            sb.appendLine("$indent  <styleUrl>#operator</styleUrl>")
            sb.appendLine("$indent  <description><![CDATA[")
            sb.appendLine("$indent    <b>Operator of:</b> $label<br/>")
            d.operatorId?.let { sb.appendLine("$indent    <b>Operator ID:</b> ${xml(it)}<br/>") }
            sb.appendLine("$indent    <i>Position reported in the drone's Remote ID System message.</i><br/>")
            sb.appendLine("$indent  ]]></description>")
            sb.appendLine("$indent  <Point><coordinates>${fmt(d.operatorLongitude!!)},${fmt(d.operatorLatitude!!)},0</coordinates></Point>")
            sb.appendLine("$indent</Placemark>")
            if (d.hasTargetLocation) {
                sb.appendLine("$indent<Placemark>")
                sb.appendLine("$indent  <name>$label ↔ operator</name>")
                sb.appendLine("$indent  <styleUrl>#operator-link</styleUrl>")
                sb.appendLine("$indent  <LineString><tessellate>1</tessellate><coordinates>")
                sb.appendLine("$indent    ${fmt(d.targetLongitude!!)},${fmt(d.targetLatitude!!)},0 ${fmt(d.operatorLongitude)},${fmt(d.operatorLatitude)},0")
                sb.appendLine("$indent  </coordinates></LineString>")
                sb.appendLine("$indent</Placemark>")
            }
        }
    }

    fun kmlStyleId(category: DeviceCategory): String = when (category) {
        DeviceCategory.FLOCK_ALPR -> "flock"
        DeviceCategory.GUNSHOT_DETECTOR -> "raven"
        DeviceCategory.LAW_ENFORCEMENT -> "le"
        DeviceCategory.WEARABLE_CAMERA -> "wearable"
        DeviceCategory.DRONE -> "drone"
    }

    // ------------------------------------------------------------------

    fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /** "20260910T160025Z" for file names. */
    fun isoCompact(epochMs: Long): String = iso(epochMs).replace("-", "").replace(":", "").substringBefore('.').removeSuffix("Z") + "Z"

    fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    fun fmt(d: Double): String = String.format(Locale.ROOT, "%.6f", d)
}
