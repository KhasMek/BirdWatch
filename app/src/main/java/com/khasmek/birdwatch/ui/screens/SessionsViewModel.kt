package com.khasmek.birdwatch.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.BackupPayload
import com.khasmek.birdwatch.data.BackupReader
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.ExportReader
import com.khasmek.birdwatch.data.ImportFormatException
import com.khasmek.birdwatch.data.ImportedSession
import com.khasmek.birdwatch.data.ParsedBackup
import com.khasmek.birdwatch.data.RestoreResult
import com.khasmek.birdwatch.data.SessionManager
import com.khasmek.birdwatch.data.SessionSummary
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.usb.UsbCompanion
import com.khasmek.birdwatch.usb.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Device counts per category plus totals, loaded on demand for the backup / delete dialogs. */
data class DataCounts(val byCategory: Map<DeviceCategory, Int> = emptyMap(), val sessions: Int = 0, val devices: Int = 0)

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val usb: UsbState = UsbState(),
    /** Any long-running import / backup / restore / delete in flight. */
    val busy: Boolean = false,
    val counts: DataCounts = DataCounts(),
)

/** Sessions list: every session newest first, with counts; plus import, backup, restore, delete-all. */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionManager = container.sessionManager
    private val backupManager = container.backupManager
    private val busy = MutableStateFlow(false)
    private val counts = MutableStateFlow(DataCounts())

    /** Held between "prepare backup" and the user picking a destination. */
    private var pendingBackup: BackupPayload? = null

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionManager.sessionSummaries,
        sessionManager.currentSession,
        container.usbCompanion.state,
        busy,
        counts,
    ) { sessions, current, usb, b, c -> SessionsUiState(sessions, current?.id, usb, b, c) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

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
            counts.value = DataCounts(by, s, d)
        }
    }

    // ---- import -----------------------------------------------------------

    /** Pull the ESP32's table; the result (or failure message) goes to [onDone]. */
    fun importFromEsp32(source: UsbCompanion.DumpSource, onDone: (Result<SessionManager.ImportResult>) -> Unit) =
        runBusy(onDone) { sessionManager.importFromEsp32(source) }

    /** Restore a single session from a BirdWatch JSON/CSV export the user picked. */
    fun importFile(uri: Uri, onDone: (Result<ImportedSession>) -> Unit) = runBusy(onDone) {
        val (text, name) = readDocument(uri)
        sessionManager.importExported(ExportReader.parse(text, name))
    }

    // ---- backup / restore ---------------------------------------------------

    /** Build the backup in memory; the screen then asks the user where to save it. */
    fun prepareBackup(categories: Set<DeviceCategory>, format: ExportFormat, onReady: (Result<BackupPayload>) -> Unit) =
        runBusy(onReady) { backupManager.createBackup(categories, format).also { pendingBackup = it } }

    /** The user picked a destination for the prepared backup. */
    fun writeBackup(uri: Uri?, onDone: (Result<BackupPayload>) -> Unit) {
        val payload = pendingBackup
        pendingBackup = null
        if (uri == null || payload == null) return // picker cancelled
        runBusy(onDone) { backupManager.writeTo(uri, payload); payload }
    }

    /** Parse a backup (or single export) the user picked, for the restore dialog. */
    fun parseBackup(uri: Uri, onDone: (Result<ParsedBackup>) -> Unit) = runBusy(onDone) {
        val (text, name) = readDocument(uri)
        BackupReader.parse(text, name)
    }

    fun restore(backup: ParsedBackup, categories: Set<DeviceCategory>, onDone: (Result<RestoreResult>) -> Unit) =
        runBusy(onDone) { backupManager.restore(backup, categories) }

    /** Stop any running session, then wipe every session and detection. */
    fun deleteAll(onDone: (Result<Unit>) -> Unit) = runBusy(onDone) {
        sessionManager.stopSuspending()
        backupManager.deleteAll()
    }

    // ---- helpers --------------------------------------------------------------

    private fun <T> runBusy(onDone: (Result<T>) -> Unit, block: suspend () -> T) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            val result = runCatching { block() }
            busy.value = false
            onDone(result)
        }
    }

    private suspend fun readDocument(uri: Uri): Pair<String, String?> = withContext(Dispatchers.IO) {
        val resolver = container.appContext.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw ImportFormatException("Could not open the file")
        if (bytes.size > MAX_IMPORT_BYTES) throw ImportFormatException("File is too large to be a BirdWatch export")
        String(bytes, Charsets.UTF_8) to name
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 50 * 1024 * 1024
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
