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

data class RestoreResult(val sessionsAdded: Int, val sessionsMerged: Int, val devicesAdded: Int, val devicesUpdated: Int)

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

    suspend fun createBackup(categories: Set<DeviceCategory>, format: ExportFormat, now: Long = System.currentTimeMillis()): BackupPayload {
        val sessions = db.sessionDao().getAll()
        val bundles = sessions.map { s ->
            SessionBundle(s, db.detectionDao().getBySession(s.id).filter { it.deviceType.category in categories })
        }.filter { it.devices.isNotEmpty() }
        return BackupPayload(
            fileName = BackupWriter.fileName(format, now),
            format = format,
            text = BackupWriter.write(format, bundles, categories, now),
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

    suspend fun restore(backup: ParsedBackup, categories: Set<DeviceCategory>): RestoreResult {
        val filtered = backup.filtered(categories)
        var sessionsAdded = 0; var sessionsMerged = 0; var devicesAdded = 0; var devicesUpdated = 0
        db.withTransaction {
            filtered.sessions.forEach { b ->
                if (db.sessionDao().getById(b.session.id) == null) {
                    db.sessionDao().insert(b.session.copy(label = b.session.label ?: "Restored (${backup.format.label})"))
                    sessionsAdded++
                } else {
                    sessionsMerged++
                }
                val existing = db.detectionDao().getBySession(b.session.id).map { it.macAddress }.toSet()
                b.devices.forEach { if (it.macAddress in existing) devicesUpdated++ else devicesAdded++ }
                if (b.devices.isNotEmpty()) db.detectionDao().upsertAll(b.devices)
            }
        }
        Log.i(TAG, "Restore: +$sessionsAdded sessions, $sessionsMerged merged, +$devicesAdded devices, $devicesUpdated updated")
        return RestoreResult(sessionsAdded, sessionsMerged, devicesAdded, devicesUpdated)
    }

    /** Wipe every session and detection. The caller stops a running session first. */
    suspend fun deleteAll() {
        db.withTransaction {
            db.detectionDao().deleteAll()
            db.sessionDao().deleteAll()
        }
        Log.i(TAG, "All sessions and detections deleted")
    }

    companion object {
        private const val TAG = "BirdWatch/Backup"
    }
}
