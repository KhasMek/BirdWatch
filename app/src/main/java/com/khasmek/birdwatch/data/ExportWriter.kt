package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
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

/**
 * Pure serialisers for a session's detections. No Android dependencies; JVM-tested.
 * Field names follow the upstream Flask dashboard exports where they overlap
 * (`mac_address`, `detection_method`, `detection_tier`, `rssi`, `latitude`, `longitude`).
 */
object ExportWriter {

    private val json = Json { prettyPrint = true }

    fun write(format: ExportFormat, session: ScanSession, devices: List<DetectedDevice>, exportedAt: Long = System.currentTimeMillis()): String =
        when (format) {
            ExportFormat.JSON -> json(session, devices, exportedAt)
            ExportFormat.CSV -> csv(devices)
            ExportFormat.KML -> kml(session, devices)
        }

    fun fileName(format: ExportFormat, session: ScanSession): String =
        "birdwatch_${isoCompact(session.startedAt)}_${session.id.take(8)}.${format.extension}"

    // ------------------------------------------------------------------

    fun json(session: ScanSession, devices: List<DetectedDevice>, exportedAt: Long): String {
        val root = buildJsonObject {
            put("app", "birdwatch")
            put("exported_at", iso(exportedAt))
            put("session", buildJsonObject {
                put("id", session.id)
                put("started_at", iso(session.startedAt))
                put("ended_at", session.endedAt?.let { JsonPrimitive(iso(it)) } ?: JsonNull)
                put("device_count", devices.size)
            })
            put("devices", buildJsonArray {
                devices.forEach { d ->
                    add(buildJsonObject {
                        put("mac_address", d.macAddress)
                        put("device_name", d.deviceName?.let { JsonPrimitive(it) } ?: JsonNull)
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
                        put("latitude", d.latitude?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("longitude", d.longitude?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("gps_accuracy_m", d.accuracyMeters?.let { JsonPrimitive(it) } ?: JsonNull)
                        put("first_seen", iso(d.firstSeen))
                        put("last_seen", iso(d.lastSeen))
                        put("sightings", d.sightings)
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
                    })
                }
            })
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), root)
    }

    val CSV_HEADER = listOf(
        "session_id", "mac_address", "device_name", "source", "detection_method", "device_type", "category",
        "confidence", "matched_on", "raven_fw", "detection_tier", "channel", "rssi",
        "latitude", "longitude", "gps_accuracy_m", "first_seen", "last_seen", "sightings",
        "uas_id", "operator_id", "target_latitude", "target_longitude", "target_altitude_m",
        "operator_latitude", "operator_longitude",
    )

    fun csv(devices: List<DetectedDevice>): String = buildString {
        appendLine(CSV_HEADER.joinToString(","))
        devices.forEach { d ->
            appendLine(
                listOf(
                    d.sessionId, d.macAddress, d.deviceName ?: "", d.source.name, d.detectionMethod.wireName,
                    d.deviceType.name, d.deviceType.category.name, d.confidence.name, d.matchedOn, d.ravenFirmware ?: "",
                    d.tier?.toString() ?: "", d.channel?.toString() ?: "", d.rssi.toString(),
                    d.latitude?.let { fmt(it) } ?: "", d.longitude?.let { fmt(it) } ?: "",
                    d.accuracyMeters?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
                    iso(d.firstSeen), iso(d.lastSeen), d.sightings.toString(),
                    d.uasId ?: "", d.operatorId ?: "",
                    d.targetLatitude?.let { fmt(it) } ?: "", d.targetLongitude?.let { fmt(it) } ?: "",
                    d.targetAltitudeM?.let { String.format(Locale.ROOT, "%.1f", it) } ?: "",
                    d.operatorLatitude?.let { fmt(it) } ?: "", d.operatorLongitude?.let { fmt(it) } ?: "",
                ).joinToString(",") { csvCell(it) }
            )
        }
    }

    fun kml(session: ScanSession, devices: List<DetectedDevice>): String = buildString {
        val located = devices.filter { it.hasLocation || it.hasTargetLocation }
        val operators = devices.filter { it.hasOperatorLocation }
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        appendLine("<Document>")
        appendLine("  <name>${xml("BirdWatch session " + isoCompact(session.startedAt))}</name>")
        appendLine("  <description>${xml("${devices.size} devices, ${located.size} with GPS. Session ${session.id}")}</description>")
        KML_STYLES.forEach { (id, style) -> appendLine("  <Style id=\"$id\"><IconStyle><color>${style.first}</color><scale>1.1</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/${style.second}.png</href></Icon></IconStyle></Style>") }
        appendLine("""  <Style id="operator"><IconStyle><color>ff8f40e9</color><scale>1.0</scale><Icon><href>http://maps.google.com/mapfiles/kml/paddle/pink-circle.png</href></Icon></IconStyle></Style>""")
        appendLine("""  <Style id="operator-link"><LineStyle><color>b37a6e54</color><width>3</width></LineStyle></Style>""")
        located.forEach { d ->
            appendLine("  <Placemark>")
            appendLine("    <name>${xml("${d.deviceType.label}: ${d.displayName}")}</name>")
            appendLine("    <styleUrl>#${kmlStyleId(d.deviceType.category)}</styleUrl>")
            appendLine("    <TimeStamp><when>${iso(d.lastSeen)}</when></TimeStamp>")
            appendLine("    <description><![CDATA[")
            appendLine("      <b>MAC:</b> ${d.macAddress}<br/>")
            appendLine("      <b>Category:</b> ${d.deviceType.category.label}<br/>")
            appendLine("      <b>Source:</b> ${d.source.label}<br/>")
            appendLine("      <b>Method:</b> ${d.detectionMethod.label} (${d.matchedOn})<br/>")
            appendLine("      <b>Confidence:</b> ${d.confidence.name}<br/>")
            d.tier?.let { appendLine("      <b>Tier:</b> $it<br/>") }
            d.channel?.let { appendLine("      <b>Channel:</b> $it<br/>") }
            d.ravenFirmware?.let { appendLine("      <b>Raven FW:</b> $it<br/>") }
            appendLine("      <b>RSSI:</b> ${d.rssi} dBm<br/>")
            appendLine("      <b>Sightings:</b> ${d.sightings}<br/>")
            appendLine("      <b>First seen:</b> ${iso(d.firstSeen)}<br/>")
            appendLine("      <b>Last seen:</b> ${iso(d.lastSeen)}<br/>")
            d.accuracyMeters?.let { appendLine("      <b>GPS accuracy:</b> ${String.format(Locale.ROOT, "%.0f", it)} m<br/>") }
            if (d.isRemoteId) {
                d.uasId?.let { appendLine("      <b>UAS ID:</b> ${xml(it)}<br/>") }
                d.operatorId?.let { appendLine("      <b>Operator ID:</b> ${xml(it)}<br/>") }
                if (d.hasOperatorLocation) appendLine("      <b>Operator position:</b> ${fmt(d.operatorLatitude!!)}, ${fmt(d.operatorLongitude!!)}<br/>")
                if (d.hasTargetLocation) appendLine("      <i>Placemark is the drone's self-reported position.</i><br/>")
            }
            appendLine("    ]]></description>")
            // Drones are placed where they say they are (with altitude); everything else where the phone was.
            if (d.hasTargetLocation) {
                appendLine("    <Point><altitudeMode>absolute</altitudeMode><coordinates>${fmt(d.targetLongitude!!)},${fmt(d.targetLatitude!!)},${String.format(Locale.ROOT, "%.0f", d.targetAltitudeM ?: 0.0)}</coordinates></Point>")
            } else {
                appendLine("    <Point><coordinates>${fmt(d.longitude!!)},${fmt(d.latitude!!)},0</coordinates></Point>")
            }
            appendLine("  </Placemark>")
        }
        // Remote ID operators: their own placemark plus a dashed-style line back to the aircraft.
        operators.forEach { d ->
            val label = xml(d.uasId ?: d.displayName)
            appendLine("  <Placemark>")
            appendLine("    <name>Operator: $label</name>")
            appendLine("    <styleUrl>#operator</styleUrl>")
            appendLine("    <description><![CDATA[")
            appendLine("      <b>Operator of:</b> $label<br/>")
            d.operatorId?.let { appendLine("      <b>Operator ID:</b> ${xml(it)}<br/>") }
            appendLine("      <i>Position reported in the drone's Remote ID System message.</i><br/>")
            appendLine("    ]]></description>")
            appendLine("    <Point><coordinates>${fmt(d.operatorLongitude!!)},${fmt(d.operatorLatitude!!)},0</coordinates></Point>")
            appendLine("  </Placemark>")
            if (d.hasTargetLocation) {
                appendLine("  <Placemark>")
                appendLine("    <name>$label ↔ operator</name>")
                appendLine("    <styleUrl>#operator-link</styleUrl>")
                appendLine("    <LineString><tessellate>1</tessellate><coordinates>")
                appendLine("      ${fmt(d.targetLongitude!!)},${fmt(d.targetLatitude!!)},0 ${fmt(d.operatorLongitude)},${fmt(d.operatorLatitude)},0")
                appendLine("    </coordinates></LineString>")
                appendLine("  </Placemark>")
            }
        }
        appendLine("</Document>")
        appendLine("</kml>")
    }

    // ------------------------------------------------------------------

    /** KML style id -> (aabbggrr colour, Google paddle icon name). */
    private val KML_STYLES: Map<String, Pair<String, String>> = mapOf(
        "flock" to ("ff0051e6" to "orange-circle"),
        "raven" to ("ff9a1b6a" to "purple-circle"),
        "le" to ("ffc06515" to "blu-circle"),
        "wearable" to ("ff7b8900" to "ltblu-circle"),
        "drone" to ("ff7a6e54" to "ylw-circle"),
    )

    fun kmlStyleId(category: DeviceCategory): String = when (category) {
        DeviceCategory.FLOCK_ALPR -> "flock"
        DeviceCategory.GUNSHOT_DETECTOR -> "raven"
        DeviceCategory.LAW_ENFORCEMENT -> "le"
        DeviceCategory.WEARABLE_CAMERA -> "wearable"
        DeviceCategory.DRONE -> "drone"
    }

    fun iso(epochMs: Long): String = Instant.ofEpochMilli(epochMs).toString()

    /** "20260910T160025Z" for file names. */
    fun isoCompact(epochMs: Long): String = iso(epochMs).replace("-", "").replace(":", "").substringBefore('.').removeSuffix("Z") + "Z"

    fun csvCell(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    fun xml(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")

    private fun fmt(d: Double) = String.format(Locale.ROOT, "%.6f", d)
}
