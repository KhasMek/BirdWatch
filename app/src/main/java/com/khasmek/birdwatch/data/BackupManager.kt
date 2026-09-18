package com.khasmek.birdwatch.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.khasmek.birdwatch.detection.DeviceCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A backup ready to be written: the user picks where it goes. */
data class BackupPayload(val fileName: String, val format: ExportFormat, val text: String, val sessionCount: Int, val deviceCount: Int)

data class RestoreResult(
    val sessionsAdded: Int,
    val sessionsMerged: Int,
    val devicesAdded: Int,
    val devicesUpdated: Int,
    /** Per-device edits (alias / moved pin / hidden) written because the file's were newer or new. */
    val editsApplied: Int = 0,
)

/**
 * Whole-database backup and restore, filtered by detection category.
 *
 * Restore MERGES: sessions that don't exist are inserted, devices are upserted into sessions
 * that do (keyed by session + MAC). So restoring a Flock-only backup into a phone that already
 * has the Raven rows for the same sessions leaves those untouched.
 */
class BackupManager(context: Context, private val db: DetectionDatabase) {

    private val appContext = context.applicationContext

    /** Device counts per category across the whole database, for the backup picker. */
    suspend fun categoryCounts(): Map<DeviceCategory, Int> =
        db.detectionDao().countByType().groupBy { it.deviceType.category }.mapValues { (_, rows) -> rows.sumOf { it.count } }

    suspend fun totals(): Pair<Int, Int> = db.sessionDao().count() to db.detectionDao().count()

    /** Query and serialise on a background dispatcher; the text can be large. */
    suspend fun createBackup(
        categories: Set<DeviceCategory>,
        format: ExportFormat,
        now: Long = System.currentTimeMillis(),
        fileName: String = BackupWriter.fileName(format, now),
    ): BackupPayload = withContext(Dispatchers.Default) {
        val sessions = db.sessionDao().getAll()
        val bySession = db.detectionDao().getAll().groupBy { it.sessionId }
        val overrides = db.deviceOverrideDao().getAll().associateBy { it.macAddress }
        val samplesBySession = if (format == ExportFormat.JSON) db.sightingSampleDao().getAll().groupBy { it.sessionId } else emptyMap()
        val bundles = sessions.map { s ->
            val devices = bySession[s.id].orEmpty().filter { it.deviceType.category in categories }
            val macs = devices.map { it.macAddress }.toSet()
            SessionBundle(s, devices, samplesBySession[s.id].orEmpty().filter { it.macAddress in macs })
        }.filter { it.devices.isNotEmpty() }
        BackupPayload(
            fileName = fileName,
            format = format,
            text = BackupWriter.write(format, bundles, categories, now, overrides),
            sessionCount = bundles.size,
            deviceCount = bundles.sumOf { it.devices.size },
        )
    }

    /** Write [payload] to a document the user chose (Storage Access Framework uri). */
    suspend fun writeTo(uri: Uri, payload: BackupPayload) = withContext(Dispatchers.IO) {
        appContext.contentResolver.openOutputStream(uri, "wt")?.use { it.write(payload.text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Could not open the destination for writing")
        Log.i(TAG, "Backup written: ${payload.fileName} (${payload.sessionCount} sessions, ${payload.deviceCount} devices)")
    }

    /**
     * Merge [backup] into the database. Missing sessions and detections are inserted; a detection
     * that already exists is combined with [mergeDetection], so a row that kept accumulating
     * sightings after the backup was taken is never regressed. `devicesUpdated` counts only rows
     * that actually changed.
     */
    suspend fun restore(backup: ParsedBackup, categories: Set<DeviceCategory>): RestoreResult {
        val filtered = backup.filtered(categories)
        var sessionsAdded = 0; var sessionsMerged = 0; var devicesAdded = 0; var devicesUpdated = 0; var editsApplied = 0
        db.withTransaction {
            filtered.sessions.forEach { b ->
                if (db.sessionDao().getById(b.session.id) == null) {
                    db.sessionDao().insert(b.session.copy(label = b.session.label ?: "Restored (${backup.format.label})", origin = SessionOrigin.RESTORE))
                    sessionsAdded++
                } else {
                    sessionsMerged++
                }
                val existing = db.detectionDao().getBySession(b.session.id).associateBy { it.macAddress }
                val toWrite = b.devices.mapNotNull { incoming ->
                    val local = existing[incoming.macAddress]
                    when {
                        local == null -> { devicesAdded++; incoming }
                        else -> mergeDetection(local, incoming).takeIf { it != local }?.also { devicesUpdated++ }
                    }
                }
                if (toWrite.isNotEmpty()) db.detectionDao().upsertAll(toWrite)
                restoreSamples(db, b)
            }
            editsApplied = applyOverrides(db, filtered.overrides)
        }
        Log.i(TAG, "Restore: +$sessionsAdded sessions, $sessionsMerged merged, +$devicesAdded devices, $devicesUpdated updated, $editsApplied edits")
        return RestoreResult(sessionsAdded, sessionsMerged, devicesAdded, devicesUpdated, editsApplied)
    }

    /** Wipe every session, detection and per-device edit. The caller stops a running session first. */
    suspend fun deleteAll() {
        db.withTransaction {
            db.detectionDao().deleteAll()
            db.sightingSampleDao().deleteAll()
            db.sessionDao().deleteAll()
            db.deviceOverrideDao().deleteAll()
        }
        Log.i(TAG, "All sessions, detections, trails and device edits deleted")
    }

    companion object {
        private const val TAG = "BirdWatch/Backup"

        /**
         * Insert a bundle's signal trails for devices that have none locally in that session.
         * Trails are never merged point by point: the local trail wins if it exists.
         */
        suspend fun restoreSamples(db: DetectionDatabase, bundle: SessionBundle) {
            if (bundle.samples.isEmpty()) return
            val dao = db.sightingSampleDao()
            bundle.samples.groupBy { it.macAddress }.forEach { (mac, rows) ->
                if (dao.count(bundle.session.id, mac) == 0) {
                    dao.insertAll(rows.map { it.copy(id = 0, sessionId = bundle.session.id) }.take(SightingTrail.MAX_PER_DEVICE_PER_SESSION))
                }
            }
        }

        /**
         * Write the file's per-device edits, but never over a newer edit made on this phone: an
         * incoming override wins only when the MAC has none locally or its `updatedAt` is later.
         * Returns how many were written. Call inside a transaction.
         */
        suspend fun applyOverrides(db: DetectionDatabase, incoming: List<DeviceOverride>): Int {
            var applied = 0
            val dao = db.deviceOverrideDao()
            incoming.forEach { o ->
                if (o.isEmpty) return@forEach
                val local = dao.get(o.macAddress)
                if (local == null || o.updatedAt > local.updatedAt) {
                    dao.upsert(o)
                    applied++
                }
            }
            return applied
        }
    }
}
