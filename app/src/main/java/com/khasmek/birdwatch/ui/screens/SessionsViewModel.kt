package com.khasmek.birdwatch.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.BackupPayload
import com.khasmek.birdwatch.data.BackupReader
import com.khasmek.birdwatch.data.BackupWriter
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.ExportReader
import com.khasmek.birdwatch.data.ImportFormatException
import com.khasmek.birdwatch.data.ParsedBackup
import com.khasmek.birdwatch.data.SessionManager
import com.khasmek.birdwatch.data.SessionSummary
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.usb.UsbCompanion
import com.khasmek.birdwatch.usb.UsbState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/** Device counts per category plus totals, loaded on demand for the backup / delete dialogs. */
data class DataCounts(val byCategory: Map<DeviceCategory, Int> = emptyMap(), val sessions: Int = 0, val devices: Int = 0)

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val usb: UsbState = UsbState(),
    /** Any long-running import / backup / restore / delete in flight. */
    val busy: Boolean = false,
    val counts: DataCounts = DataCounts(),
    /** A backup is built and the screen should open the "save as" picker for it (once). */
    val saveRequest: BackupPayload? = null,
    /** A backup has been parsed and the restore dialog should be showing. */
    val parsedRestore: ParsedBackup? = null,
)

/**
 * Sessions list plus the long-running operations behind the overflow menu.
 *
 * Results never go back through callbacks captured by the screen: the screen may have left
 * composition by the time an import or backup finishes (its activity-result launchers are
 * unregistered then, and launching one would throw). Instead outcomes surface as [uiState]
 * fields the screen reacts to while composed, and one-line [messages] it toasts.
 */
class SessionsViewModel(private val container: AppContainer, private val savedState: SavedStateHandle) : ViewModel() {

    private val sessionManager = container.sessionManager
    private val backupManager = container.backupManager

    private data class Work(
        val busy: Boolean = false,
        val counts: DataCounts = DataCounts(),
        val saveRequest: BackupPayload? = null,
        val parsedRestore: ParsedBackup? = null,
    )

    private val work = MutableStateFlow(Work())

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-shot user-facing outcomes ("Imported 3 devices…", "Backup failed: …"). */
    val messages: Flow<String> = _messages.receiveAsFlow()

    /**
     * Held between "backup built" and "user picked a destination". The picker is another
     * activity; if our process dies meanwhile the payload is rebuilt from [savedState].
     */
    private var savePayload: BackupPayload? = null

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionManager.sessionSummaries,
        sessionManager.currentSession,
        container.usbCompanion.state,
        work,
    ) { sessions, current, usb, w ->
        SessionsUiState(sessions, current?.id, usb, w.busy, w.counts, w.saveRequest, w.parsedRestore)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    suspend fun export(sessionId: String, format: ExportFormat): Intent? =
        container.exportManager.export(sessionId, format)

    fun delete(sessionId: String) {
        viewModelScope.launch { sessionManager.deleteSession(sessionId) }
    }

    /** Make sure the ESP32 is open (no-op if already connected or absent). */
    fun connectUsb() = container.usbCompanion.connect()

    /** Reload the per-category and total counts shown in the backup / delete dialogs. */
    fun refreshCounts() {
        viewModelScope.launch {
            val by = backupManager.categoryCounts()
            val (s, d) = backupManager.totals()
            work.update { it.copy(counts = DataCounts(by, s, d)) }
        }
    }

    // ---- import -----------------------------------------------------------

    /** Pull the ESP32's table into a new session. */
    fun importFromEsp32(source: UsbCompanion.DumpSource) = runBusy("Import failed") {
        val r = sessionManager.importFromEsp32(source)
        when {
            r.imported == 0 -> "ESP32 ${source.label} is empty; nothing to import"
            r.announced > r.imported ->
                "Imported ${plural(r.imported, "device")} of ${r.announced} on the ESP32 ${source.label}; try again for the rest"
            else -> "Imported ${plural(r.imported, "device")} from ESP32 ${source.label}"
        }
    }

    /** Restore a single session from a BirdWatch JSON/CSV export the user picked. */
    fun importFile(uri: Uri) = runBusy("Import failed") {
        val (text, name) = readDocument(uri)
        val parsed = withContext(Dispatchers.Default) { ExportReader.parse(text, name) }
        val r = sessionManager.importExported(parsed)
        "Imported ${plural(r.devices.size, "device")} from ${r.format.label}"
    }

    // ---- backup / restore ---------------------------------------------------

    /** Build the backup in memory; the screen then asks the user where to save it. */
    fun prepareBackup(categories: Set<DeviceCategory>, format: ExportFormat) = runBusy("Backup failed") {
        val payload = backupManager.createBackup(categories, format)
        savePayload = payload
        savedState[KEY_SAVE_CATEGORIES] = ArrayList(categories.map { it.name })
        savedState[KEY_SAVE_FORMAT] = format.name
        savedState[KEY_SAVE_FILE] = payload.fileName
        work.update { it.copy(saveRequest = payload) }
        null
    }

    /** The screen has launched the picker for [SessionsUiState.saveRequest]; don't launch it again. */
    fun saveRequestHandled() = work.update { it.copy(saveRequest = null) }

    /** The user picked a destination (or cancelled, null) for the prepared backup. */
    fun writeBackup(uri: Uri?) {
        val categories = savedState.get<ArrayList<String>>(KEY_SAVE_CATEGORIES)
        val format = savedState.get<String>(KEY_SAVE_FORMAT)?.let { name -> ExportFormat.entries.firstOrNull { it.name == name } }
        val fileName = savedState.get<String>(KEY_SAVE_FILE)
        val payload = savePayload
        savePayload = null
        savedState.remove<ArrayList<String>>(KEY_SAVE_CATEGORIES)
        savedState.remove<String>(KEY_SAVE_FORMAT)
        savedState.remove<String>(KEY_SAVE_FILE)
        if (uri == null) return // picker cancelled; the document was not created

        runBusy("Backup failed") {
            val p = payload ?: rebuildPayload(categories, format, fileName)
            if (p == null) {
                // Nothing to write: remove the empty document the picker already created.
                runCatching { DocumentsContract.deleteDocument(container.appContext.contentResolver, uri) }
                    .onFailure { Log.w(TAG, "Could not delete the empty backup document", it) }
                throw IllegalStateException("The backup was lost before it could be saved; please back up again")
            }
            backupManager.writeTo(uri, p)
            "Backed up ${plural(p.sessionCount, "session")}, ${plural(p.deviceCount, "device")} to ${p.fileName}"
        }
    }

    /** After process death the payload is gone but its recipe survived in [savedState]. */
    private suspend fun rebuildPayload(categories: ArrayList<String>?, format: ExportFormat?, fileName: String?): BackupPayload? {
        if (categories == null || format == null) return null
        val cats = categories.mapNotNull { name -> DeviceCategory.entries.firstOrNull { it.name == name } }.toSet()
        if (cats.isEmpty()) return null
        Log.i(TAG, "Rebuilding backup payload after process death")
        return backupManager.createBackup(cats, format, fileName = fileName ?: BackupWriter.fileName(format, System.currentTimeMillis()))
    }

    /** Parse a backup (or single export) the user picked; the restore dialog shows the result. */
    fun parseBackup(uri: Uri) = runBusy("Can't restore") {
        val (text, name) = readDocument(uri)
        val parsed = withContext(Dispatchers.Default) { BackupReader.parse(text, name) }
        work.update { it.copy(parsedRestore = parsed) }
        null
    }

    fun dismissRestore() = work.update { it.copy(parsedRestore = null) }

    /** Merge the parsed backup, keeping only [categories]. */
    fun restore(categories: Set<DeviceCategory>) {
        val parsed = work.value.parsedRestore ?: return
        work.update { it.copy(parsedRestore = null) }
        runBusy("Restore failed") {
            val r = backupManager.restore(parsed, categories)
            "Restored: ${plural(r.sessionsAdded, "new session")}, ${plural(r.sessionsMerged, "merged")}, " +
                "${plural(r.devicesAdded, "device")} added, ${r.devicesUpdated} updated"
        }
    }

    /** Stop any running session, then wipe every session and detection. */
    fun deleteAll() = runBusy("Delete failed") {
        sessionManager.stopSuspending()
        backupManager.deleteAll()
        "All sessions and detections deleted"
    }

    // ---- helpers --------------------------------------------------------------

    /**
     * Run one operation at a time with the busy flag up. [block] returns the success message
     * (or null for none); any exception becomes "[failPrefix]: reason".
     */
    private fun runBusy(failPrefix: String, block: suspend () -> String?) {
        if (work.value.busy) return
        work.update { it.copy(busy = true) }
        viewModelScope.launch {
            val message = try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e !is ImportFormatException && e !is SessionManager.AlreadyImportedException) Log.e(TAG, failPrefix, e)
                "$failPrefix: ${e.message ?: e::class.simpleName}"
            } finally {
                work.update { it.copy(busy = false) }
            }
            if (message != null) _messages.send(message)
        }
    }

    /** Read a user-picked document, refusing anything over [MAX_IMPORT_BYTES] before buffering it. */
    private suspend fun readDocument(uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        val resolver = container.appContext.contentResolver
        var name: String? = null
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                name = c.getString(0)
                if (!c.isNull(1)) declaredSize = c.getLong(1)
            }
        }
        if (declaredSize > MAX_IMPORT_BYTES) throw ImportFormatException("File is too large to be a BirdWatch export")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val out = ByteArrayOutputStream(if (declaredSize > 0) declaredSize.toInt() else 64 * 1024)
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (out.size() + n > MAX_IMPORT_BYTES) throw ImportFormatException("File is too large to be a BirdWatch export")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        } ?: throw ImportFormatException("Could not open the file")
        String(bytes, Charsets.UTF_8) to name
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    private companion object {
        const val TAG = "BirdWatch/Sessions"
        const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
        const val KEY_SAVE_CATEGORIES = "save_categories"
        const val KEY_SAVE_FORMAT = "save_format"
        const val KEY_SAVE_FILE = "save_file"
    }
}

data class SessionDetailUiState(
    val summary: SessionSummary? = null,
    val devices: List<DetectedDevice> = emptyList(),
    val isActive: Boolean = false,
    /** True once the first query has returned, so a deleted/missing session can be told apart from "loading". */
    val loaded: Boolean = false,
)

/** One session: summary header + its devices. */
class SessionDetailViewModel(private val container: AppContainer, private val sessionId: String) : ViewModel() {

    private val sessionManager = container.sessionManager

    val uiState: StateFlow<SessionDetailUiState> = combine(
        container.database.sessionDao().observeSummary(sessionId),
        sessionManager.observeSessionDevices(sessionId),
        sessionManager.currentSession.map { it?.id == sessionId },
    ) { summary, devices, active ->
        SessionDetailUiState(summary = summary, devices = devices, isActive = active, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionDetailUiState())

    suspend fun export(format: ExportFormat): Intent? = container.exportManager.export(sessionId, format)

    fun delete() {
        viewModelScope.launch { sessionManager.deleteSession(sessionId) }
    }
}
