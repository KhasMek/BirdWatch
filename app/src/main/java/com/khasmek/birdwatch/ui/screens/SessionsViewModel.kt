package com.khasmek.birdwatch.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.ExportReader
import com.khasmek.birdwatch.data.ImportFormatException
import com.khasmek.birdwatch.data.ImportedSession
import com.khasmek.birdwatch.data.SessionManager
import com.khasmek.birdwatch.data.SessionSummary
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.usb.UsbCompanion
import com.khasmek.birdwatch.usb.UsbState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
    val usb: UsbState = UsbState(),
    val importing: Boolean = false,
)

/** Sessions list: every session newest first, with counts; plus ESP32 import. */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionManager = container.sessionManager
    private val importing = MutableStateFlow(false)

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionManager.sessionSummaries,
        sessionManager.currentSession,
        container.usbCompanion.state,
        importing,
    ) { sessions, current, usb, busy -> SessionsUiState(sessions, current?.id, usb, busy) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    suspend fun export(sessionId: String, format: ExportFormat): Intent? =
        container.exportManager.export(sessionId, format)

    fun delete(sessionId: String) {
        viewModelScope.launch { sessionManager.deleteSession(sessionId) }
    }

    /** Make sure the ESP32 is open (no-op if already connected or absent). */
    fun connectUsb() = container.usbCompanion.connect()

    /** Pull the ESP32's table; the result (or failure message) goes to [onDone]. */
    fun importFromEsp32(source: UsbCompanion.DumpSource, onDone: (Result<SessionManager.ImportResult>) -> Unit) {
        if (importing.value) return
        importing.value = true
        viewModelScope.launch {
            val result = runCatching { sessionManager.importFromEsp32(source) }
            importing.value = false
            onDone(result)
        }
    }

    /** Restore a session from a BirdWatch JSON/CSV export the user picked. */
    fun importFile(uri: Uri, onDone: (Result<ImportedSession>) -> Unit) {
        if (importing.value) return
        importing.value = true
        viewModelScope.launch {
            val result = runCatching {
                val (text, name) = withContext(Dispatchers.IO) {
                    val resolver = container.appContext.contentResolver
                    val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    }
                    val text = resolver.openInputStream(uri)?.use { it.readBytes() }?.let {
                        if (it.size > MAX_IMPORT_BYTES) throw ImportFormatException("File is too large to be a BirdWatch export")
                        String(it, Charsets.UTF_8)
                    } ?: throw ImportFormatException("Could not open the file")
                    text to name
                }
                sessionManager.importExported(ExportReader.parse(text, name))
            }
            importing.value = false
            onDone(result)
        }
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024
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
