package com.khasmek.flockyou.ui.screens

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.flockyou.AppContainer
import com.khasmek.flockyou.data.ExportFormat
import com.khasmek.flockyou.data.SessionSummary
import com.khasmek.flockyou.detection.DetectedDevice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionsUiState(
    val sessions: List<SessionSummary> = emptyList(),
    val activeSessionId: String? = null,
)

/** Sessions list: every session newest first, with counts. */
class SessionsViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionManager = container.sessionManager

    val uiState: StateFlow<SessionsUiState> = combine(
        sessionManager.sessionSummaries,
        sessionManager.currentSession,
    ) { sessions, current -> SessionsUiState(sessions, current?.id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionsUiState())

    suspend fun export(sessionId: String, format: ExportFormat): Intent? =
        container.exportManager.export(sessionId, format)

    fun delete(sessionId: String) {
        viewModelScope.launch { sessionManager.deleteSession(sessionId) }
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
