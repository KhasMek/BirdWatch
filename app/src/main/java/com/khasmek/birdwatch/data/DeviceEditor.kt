package com.khasmek.birdwatch.data

import android.util.Log
import androidx.room.withTransaction
import com.khasmek.birdwatch.detection.DetectionTable

/**
 * The user's edits to a device, shared by every screen that offers them (map sheet, dashboard
 * and session-detail cards). All edits are per MAC ([DeviceOverride]); deleting goes through
 * [SessionManager] so a running session forgets the device too.
 *
 * Every call returns a one-line outcome for the screen to toast.
 */
class DeviceEditor(private val db: DetectionDatabase, private val sessionManager: SessionManager) {

    private val dao get() = db.deviceOverrideDao()

    /** Pin the device at a position; every session that saw this MAC now draws it there. */
    suspend fun setLocation(mac: String, latitude: Double, longitude: Double): String =
        edit(mac, "Pin moved") { it.copy(latitude = latitude, longitude = longitude) }

    /** Back to the detected position. */
    suspend fun clearLocation(mac: String): String =
        edit(mac, "Pin back at the detected position") { it.copy(latitude = null, longitude = null) }

    suspend fun setAlias(mac: String, alias: String?): String {
        val clean = ImportSanitizer.text(alias, ImportSanitizer.MAX_ALIAS)
        return edit(mac, if (clean == null) "Alias cleared" else "Alias set") { it.copy(alias = clean) }
    }

    /** Alias and notes together, as the edit dialog saves them. Same caps as an imported file. */
    suspend fun setDetails(mac: String, alias: String?, notes: String?): String {
        val cleanAlias = ImportSanitizer.text(alias, ImportSanitizer.MAX_ALIAS)
        val cleanNotes = ImportSanitizer.text(notes, ImportSanitizer.MAX_NOTES)
        val done = when {
            cleanAlias == null && cleanNotes == null -> "Alias and notes cleared"
            cleanNotes == null -> "Alias saved"
            cleanAlias == null -> "Notes saved"
            else -> "Alias and notes saved"
        }
        return edit(mac, done) { it.copy(alias = cleanAlias, notes = cleanNotes) }
    }

    suspend fun setHidden(mac: String, hidden: Boolean): String =
        edit(mac, if (hidden) "Hidden from the map" else "Shown on the map again") { it.copy(hidden = hidden) }

    /** Start or stop keeping a signal trail for this device (the global switch overrides "off"). */
    suspend fun setTracked(mac: String, tracked: Boolean): String =
        edit(mac, if (tracked) "Recording this device's sightings" else "Stopped recording sightings") { it.copy(track = tracked) }

    /** Drop every stored trail breadcrumb. The track flags stay. */
    suspend fun clearTrails(): String {
        val n = runCatching { db.sightingSampleDao().deleteAll() }
            .onFailure { Log.e(TAG, "clear trails failed", it) }
            .getOrDefault(0)
        return if (n == 0) "No trail data to clear" else "Cleared $n trail point${if (n == 1) "" else "s"}"
    }

    suspend fun trailPointCount(): Int = db.sightingSampleDao().countAll()

    /** Remove this device's rows from [sessionIds]. */
    suspend fun deleteDetections(mac: String, sessionIds: Collection<String>): String {
        val n = runCatching { sessionManager.deleteDetections(mac, sessionIds) }
            .onFailure { Log.e(TAG, "delete failed", it) }
            .getOrDefault(0)
        return if (n == 0) "Nothing deleted" else "Deleted $n detection${if (n == 1) "" else "s"}"
    }

    private suspend fun edit(mac: String, done: String, change: (DeviceOverride) -> DeviceOverride): String {
        val key = DetectionTable.normalizeMac(mac)
        return runCatching {
            // Read-modify-write in one transaction so two quick edits (alias from the dialog, a
            // pin move from the map) cannot each overwrite the other's field.
            db.withTransaction {
                val current = dao.get(key) ?: DeviceOverride(key)
                val next = change(current).copy(updatedAt = System.currentTimeMillis())
                if (next.isEmpty) dao.delete(key) else dao.upsert(next)
            }
        }.fold(
            onSuccess = { done },
            onFailure = { Log.e(TAG, "edit failed", it); "Couldn't save: ${it.message}" },
        )
    }

    private companion object {
        const val TAG = "BirdWatch/Edit"
    }
}
