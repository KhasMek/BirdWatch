package com.khasmek.flockyou.ui.screens

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.flockyou.AppContainer
import com.khasmek.flockyou.data.ExportFormat
import com.khasmek.flockyou.data.ScanSession
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DeviceCategory
import com.khasmek.flockyou.detection.PackId
import com.khasmek.flockyou.detection.ScanStatus
import com.khasmek.flockyou.location.LocationState
import com.khasmek.flockyou.usb.UsbState
import com.khasmek.flockyou.wifi.WifiScanStatus
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
    val enabledPacks: Set<PackId> = emptySet(),
    val wifiApEnabled: Boolean = false,
    val wifi: WifiScanStatus = WifiScanStatus(),
) {
    val isActive: Boolean get() = session != null
    val totalCount: Int get() = devices.size
    val flockCount: Int get() = devices.count { it.deviceType.category == DeviceCategory.FLOCK_ALPR }
    val ravenCount: Int get() = devices.count { it.deviceType.category == DeviceCategory.GUNSHOT_DETECTOR }
    /** Hits from opt-in packs (law enforcement, wearables, ...). Never mixed into Flock/Raven. */
    val otherCount: Int get() = totalCount - flockCount - ravenCount
    val showOther: Boolean get() = enabledPacks.isNotEmpty() || otherCount > 0
    val gpsLocked: Boolean get() = location.hasFix && location.isAvailable

    val messages: List<StatusMessage>
        get() = buildList {
            scan.error?.let { add(StatusMessage("BLE: $it", isError = true)) }
            scan.warning?.let { add(StatusMessage("BLE: $it", isError = false)) }
            usb.error?.let { add(StatusMessage("USB: $it", isError = true)) }
            if (wifiApEnabled) {
                wifi.error?.let { add(StatusMessage("WiFi: $it", isError = true)) }
                wifi.warning?.let { add(StatusMessage("WiFi: $it", isError = false)) }
            }
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
        container.settings.enabledPacks,
    ) { scan, usb, location, audio, packs -> RadioState(scan, usb, location, audio, packs) }

    private val wifi = combine(container.settings.wifiApScan, container.wifiApScanner.status) { enabled, status -> enabled to status }

    val uiState: StateFlow<DashboardUiState> = combine(
        sessionManager.currentSession,
        sessionManager.observeCurrentDevices(),
        radios,
        wifi,
    ) { session, devices, r, (wifiEnabled, wifiStatus) ->
        DashboardUiState(
            session = session,
            devices = devices,
            scan = r.scan,
            usb = r.usb,
            location = r.location,
            audioEnabled = r.audio,
            enabledPacks = r.packs,
            wifiApEnabled = wifiEnabled,
            wifi = wifiStatus,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun toggleSession() {
        if (sessionManager.isActive) sessionManager.stop()
        else sessionManager.start(container.settings.scanMode)
    }

    fun toggleAudio() = container.settings.setAudioAlerts(!container.settings.audioAlerts.value)

    fun connectUsb() = container.usbCompanion.connect()

    /** Export whatever the running session has persisted so far. Null when no session is active. */
    suspend fun exportCurrentSession(format: ExportFormat): Intent? {
        val id = sessionManager.currentSession.value?.id ?: return null
        return container.exportManager.export(id, format)
    }

    private data class RadioState(
        val scan: ScanStatus,
        val usb: UsbState,
        val location: LocationState,
        val audio: Boolean,
        val packs: Set<PackId>,
    )
}
