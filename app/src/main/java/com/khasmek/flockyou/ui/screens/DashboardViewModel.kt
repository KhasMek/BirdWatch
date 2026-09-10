package com.khasmek.flockyou.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.flockyou.AppContainer
import com.khasmek.flockyou.data.ScanSession
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DeviceType
import com.khasmek.flockyou.detection.ScanStatus
import com.khasmek.flockyou.location.LocationState
import com.khasmek.flockyou.usb.UsbState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** One message line under the stats bar. */
data class StatusMessage(val text: String, val isError: Boolean)

data class DashboardUiState(
    val session: ScanSession? = null,
    val devices: List<DetectedDevice> = emptyList(),
    val scan: ScanStatus = ScanStatus(),
    val usb: UsbState = UsbState(),
    val location: LocationState = LocationState(),
    val audioEnabled: Boolean = true,
) {
    val isActive: Boolean get() = session != null
    val totalCount: Int get() = devices.size
    val flockCount: Int get() = devices.count { it.deviceType == DeviceType.FLOCK }
    val ravenCount: Int get() = devices.count { it.deviceType == DeviceType.RAVEN || it.deviceType == DeviceType.SOUNDTHINKING }
    val gpsLocked: Boolean get() = location.hasFix && location.isAvailable

    val messages: List<StatusMessage>
        get() = buildList {
            scan.error?.let { add(StatusMessage("BLE: $it", isError = true)) }
            scan.warning?.let { add(StatusMessage("BLE: $it", isError = false)) }
            usb.error?.let { add(StatusMessage("USB: $it", isError = true)) }
            location.error?.let { add(StatusMessage("GPS: $it", isError = true)) }
        }
}

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionManager = container.sessionManager

    private val radios = combine(
        container.bleScanner.status,
        container.usbCompanion.state,
        container.locationProvider.state,
        container.settings.audioAlerts,
    ) { scan, usb, location, audio -> RadioState(scan, usb, location, audio) }

    val uiState: StateFlow<DashboardUiState> = combine(
        sessionManager.currentSession,
        sessionManager.observeCurrentDevices(),
        radios,
    ) { session, devices, r ->
        DashboardUiState(
            session = session,
            devices = devices,
            scan = r.scan,
            usb = r.usb,
            location = r.location,
            audioEnabled = r.audio,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun toggleSession() {
        if (sessionManager.isActive) sessionManager.stop()
        else sessionManager.start(container.settings.scanMode)
    }

    fun toggleAudio() = container.settings.setAudioAlerts(!container.settings.audioAlerts.value)

    fun connectUsb() = container.usbCompanion.connect()

    private data class RadioState(
        val scan: ScanStatus,
        val usb: UsbState,
        val location: LocationState,
        val audio: Boolean,
    )
}
