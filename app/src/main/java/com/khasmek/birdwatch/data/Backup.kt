package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One session with its (possibly category-filtered) devices and their signal trails, the unit of a backup. */
data class SessionBundle(
    val session: ScanSession,
    val devices: List<DetectedDevice>,
    val samples: List<SightingSample> = emptyList(),
) {
    /** Trail rows keyed by MAC, for the JSON writer. */
    val trails: Trails get() = samples.groupBy { it.macAddress }
}

/**
 * Everything read from a backup file, ready for the restore dialog. [overrides] are the user's
 * per-device edits the file carried (one per MAC, the most recently updated wins).
 */
data class ParsedBackup(
    val sessions: List<SessionBundle>,
    val format: ExportFormat,
    val overrides: List<DeviceOverride> = emptyList(),
) {
    val deviceCount: Int get() = sessions.sumOf { it.devices.size }

    /** Categories present and how many devices each has, for the restore picker. */
    val categoryCounts: Map<DeviceCategory, Int>
        get() = sessions.flatMap { it.devices }.groupingBy { it.deviceType.category }.eachCount()

    /** Keep only [categories]; sessions left empty are dropped, as are edits and trails for devices no longer included. */
    fun filtered(categories: Set<DeviceCategory>): ParsedBackup {
        val kept = sessions.map { b ->
            val devices = b.devices.filter { it.deviceType.category in categories }
            val macs = devices.map { it.macAddress }.toSet()
            b.copy(devices = devices, samples = b.samples.filter { it.macAddress in macs })
        }.filter { it.devices.isNotEmpty() }
        val macs = kept.flatMap { b -> b.devices.map { it.macAddress } }.toSet()
        return copy(sessions = kept, overrides = overrides.filter { it.macAddress in macs })
    }

    companion object {
        /** One override per MAC; when a MAC appears in several sessions the newest edit wins. */
        fun mergeOverrides(all: List<DeviceOverride>): List<DeviceOverride> =
            all.groupBy { it.macAddress }.values.map { group -> group.maxBy { it.updatedAt } }
    }
}

/**
 * Multi-session backups. A JSON backup is a header plus one entry per session in the same shape
 * as a single-session export; CSV is the export CSV with several session ids; KML is one Folder
 * per session (export only). Pure Kotlin.
 */
object BackupWriter {

    const val KIND = "backup"
    const val VERSION = 1

    fun fileName(format: ExportFormat, exportedAt: Long): String =
        "birdwatch_backup_${ExportWriter.isoCompact(exportedAt)}.${format.extension}"

    fun write(
        format: ExportFormat,
        bundles: List<SessionBundle>,
        categories: Set<DeviceCategory>,
        exportedAt: Long,
        overrides: Overrides = emptyMap(),
    ): String = when (format) {
        ExportFormat.JSON -> json(bundles, categories, exportedAt, overrides)
        ExportFormat.CSV -> csv(bundles, overrides)
        ExportFormat.KML -> kml(bundles, exportedAt, overrides)
    }

    fun json(bundles: List<SessionBundle>, categories: Set<DeviceCategory>, exportedAt: Long, overrides: Overrides = emptyMap()): String {
        val root = buildJsonObject {
            put("app", "birdwatch")
            put("kind", KIND)
            put("version", VERSION)
            put("exported_at", ExportWriter.iso(exportedAt))
            put("categories", buildJsonArray { categories.sortedBy { it.ordinal }.forEach { add(JsonPrimitive(it.name)) } })
            put("session_count", bundles.size)
            put("device_count", bundles.sumOf { it.devices.size })
            put("sessions", buildJsonArray {
                bundles.forEach { b ->
                    add(buildJsonObject {
                        put("session", ExportWriter.sessionObject(b.session, b.devices.size))
                        put("devices", buildJsonArray { b.devices.forEach { add(ExportWriter.deviceObject(it, overrides[it.macAddress], b.trails[it.macAddress])) } })
                    })
                }
            })
        }
        return ExportWriter.json.encodeToString(JsonObject.serializer(), root)
    }

    fun csv(bundles: List<SessionBundle>, overrides: Overrides = emptyMap()): String =
        ExportWriter.csv(bundles.flatMap { it.devices }, overrides)

    fun kml(bundles: List<SessionBundle>, exportedAt: Long, overrides: Overrides = emptyMap()): String = buildString {
        appendLine(ExportWriter.KML_HEAD)
        appendLine("<Document>")
        appendLine("  <name>${ExportWriter.xml("BirdWatch backup " + ExportWriter.isoCompact(exportedAt))}</name>")
        appendLine("  <description>${ExportWriter.xml("${bundles.size} sessions, ${bundles.sumOf { it.devices.size }} devices")}</description>")
        ExportWriter.appendKmlStyles(this)
        bundles.forEach { b ->
            appendLine("  <Folder>")
            appendLine("    <name>${ExportWriter.xml(b.session.label ?: ("Session " + ExportWriter.isoCompact(b.session.startedAt)))}</name>")
            ExportWriter.appendKmlPlacemarks(this, b.devices, indent = "    ", overrides = overrides)
            appendLine("  </Folder>")
        }
        appendLine("</Document>")
        appendLine("</kml>")
    }
}

/**
 * Reads backups (JSON or CSV) and, for convenience, a single-session export, so picking the
 * wrong file in the Restore flow still does something sensible.
 */
object BackupReader {

    fun parse(text: String, fileName: String? = null): ParsedBackup {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return when {
            trimmed.startsWith("{") -> parseJson(trimmed)
            trimmed.startsWith("<") -> throw ImportFormatException("KML files can't be restored; use the JSON or CSV backup")
            trimmed.startsWith(ExportWriter.CSV_HEADER.first()) -> parseCsv(trimmed)
            else -> throw ImportFormatException(
                "Not a BirdWatch backup" + (fileName?.let { " ($it)" } ?: "") + "; expected the app's JSON or CSV format"
            )
        }
    }

    fun parseJson(text: String): ParsedBackup {
        val root = runCatching { ExportWriter.json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            ?: throw ImportFormatException("File is not valid JSON")
        val kind = (root["kind"] as? JsonPrimitive)?.contentOrNull
        if (kind != BackupWriter.KIND) {
            // A single-session export: wrap it.
            val single = ExportReader.parseJson(text)
            return ParsedBackup(listOf(SessionBundle(single.session, single.devices, single.samples)), ExportFormat.JSON, single.overrides)
        }
        val entries = root["sessions"] as? JsonArray ?: throw ImportFormatException("Backup has no \"sessions\" array")
        val parsedAll = entries.mapIndexed { i, el ->
            val obj = el as? JsonObject ?: throw ImportFormatException("Backup session #${i + 1} is malformed")
            if (obj["session"] !is JsonObject) throw ImportFormatException("Backup session #${i + 1} has no session object")
            // Each entry is exactly an export document minus the outer header, so reuse that parser.
            ExportReader.parseJson(obj)
        }
        return ParsedBackup(
            parsedAll.map { SessionBundle(it.session, it.devices, it.samples) },
            ExportFormat.JSON,
            ParsedBackup.mergeOverrides(parsedAll.flatMap { it.overrides }),
        )
    }

    /** CSV rows may span several sessions; each session is reconstructed from its rows. */
    fun parseCsv(text: String): ParsedBackup {
        val records = ExportReader.parseCsvRecords(text)
        if (records.size < 2) throw ImportFormatException("CSV has no rows")
        val header = records.first()
        val sid = header.indexOf("session_id")
        if (sid < 0) throw ImportFormatException("CSV is missing the \"session_id\" column; not a BirdWatch export")
        val bySession = records.drop(1).groupBy { it.getOrNull(sid).orEmpty() }
        if (bySession.keys.any { it.isEmpty() }) throw ImportFormatException("A CSV row has no session_id")
        val parsedAll = bySession.map { (_, rows) -> ExportReader.parseCsvRows(header, rows) }
        return ParsedBackup(
            parsedAll.map { SessionBundle(it.session, it.devices) },
            ExportFormat.CSV,
            ParsedBackup.mergeOverrides(parsedAll.flatMap { it.overrides }),
        )
    }
}

/**
 * Combine a detection already on the phone with the same (session, MAC) from a backup. The more
 * recently seen record wins the per-sighting fields (RSSI, GPS, Remote ID position); the span,
 * sighting count, tier and a learned name are taken from whichever record has the most.
 * Restoring an old backup therefore never regresses a row that kept scanning after the backup.
 */
fun mergeDetection(local: DetectedDevice, incoming: DetectedDevice): DetectedDevice {
    val newer = if (incoming.lastSeen > local.lastSeen) incoming else local
    val older = if (newer === incoming) local else incoming
    val best = if ((incoming.tier ?: -1) > (local.tier ?: -1)) incoming else local
    // The newer row wins, but a fix is never thrown away for a null: an ESP32 import (no GPS)
    // merged over a live row keeps the live row's coordinates.
    val fix = if (newer.hasLocation) newer else older
    return newer.copy(
        sessionId = local.sessionId,
        latitude = fix.latitude,
        longitude = fix.longitude,
        accuracyMeters = fix.accuracyMeters,
        deviceName = newer.deviceName ?: older.deviceName,
        detectionMethod = best.detectionMethod,
        confidence = best.confidence,
        matchedOn = best.matchedOn,
        tier = best.tier,
        ravenFirmware = newer.ravenFirmware ?: older.ravenFirmware,
        firstSeen = minOf(local.firstSeen, incoming.firstSeen),
        lastSeen = maxOf(local.lastSeen, incoming.lastSeen),
        sightings = maxOf(local.sightings, incoming.sightings),
        uasId = newer.uasId ?: older.uasId,
        operatorId = newer.operatorId ?: older.operatorId,
    )
}
