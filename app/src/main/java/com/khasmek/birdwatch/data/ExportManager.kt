package com.khasmek.birdwatch.data

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Writes a session export to the app's cache and returns a share-sheet intent for it, so the
 * user can save to Drive/Files, AirDrop-equivalents, email, etc. Files land in
 * `cache/exports/` (exposed through [FileProvider]) and old ones are pruned.
 */
class ExportManager(context: Context, private val db: DetectionDatabase) {

    private val appContext = context.applicationContext
    private val authority = "${appContext.packageName}.fileprovider"
    private val dir get() = File(appContext.cacheDir, "exports").apply { mkdirs() }

    /** Serialise [sessionId] in [format] to a file. Returns null if the session does not exist. */
    suspend fun exportToFile(sessionId: String, format: ExportFormat): File? = withContext(Dispatchers.IO) {
        val session = db.sessionDao().getById(sessionId) ?: return@withContext null
        val devices = db.detectionDao().getBySession(sessionId)
        // Per-device edits (moved pins, aliases, hidden) are applied on the way out.
        val macs = devices.map { it.macAddress }.toSet()
        val overrides = db.deviceOverrideDao().getAll().filter { it.macAddress in macs }.associateBy { it.macAddress }
        val file = File(dir, ExportWriter.fileName(format, session))
        file.writeText(ExportWriter.write(format, session, devices, overrides = overrides))
        prune(keep = file)
        Log.i(TAG, "Exported ${devices.size} devices to ${file.name}")
        file
    }

    /** Build an ACTION_SEND chooser for [file]. */
    fun shareIntent(file: File, format: ExportFormat): Intent {
        val uri = FileProvider.getUriForFile(appContext, authority, file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Export ${format.label}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Convenience: export then build the chooser. Null if the session is missing. */
    suspend fun export(sessionId: String, format: ExportFormat): Intent? =
        exportToFile(sessionId, format)?.let { shareIntent(it, format) }

    private fun prune(keep: File) {
        dir.listFiles()
            ?.filter { it != keep }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEPT - 1)
            ?.forEach { it.delete() }
    }

    companion object {
        private const val TAG = "BirdWatch/Export"
        private const val MAX_KEPT = 10
    }
}
