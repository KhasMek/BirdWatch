package com.khasmek.birdwatch.ui.screens

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.ScanSession
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.detection.PackId
import com.khasmek.birdwatch.detection.ScanIssue
import com.khasmek.birdwatch.detection.ScanStatus
import com.khasmek.birdwatch.location.LocationState
import com.khasmek.birdwatch.usb.UsbState
import com.khasmek.birdwatch.wifi.WifiScanStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What tapping a status message's button should do. */
enum class StatusAction(val label: String) {
    ENABLE_BLUETOOTH("Turn on"),
    LOCATION_SETTINGS("Turn on"),
    WIFI_SETTINGS("Turn on"),
    APP_SETTINGS("Open settings"),
}

/** One message line under the stats bar, optionally with a fix button. */
data class StatusMessage(val text: String, val isError: Boolean, val action: StatusAction? = null)

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
    /** Category filter for the list; null = everything. Counts always cover everything. */
    val filter: DeviceCategory? = null,
) {
    val isActive: Boolean get() = session != null
    val totalCount: Int get() = devices.size
    val flockCount: Int get() = devices.count { it.deviceType.category == DeviceCategory.FLOCK_ALPR }
    val ravenCount: Int get() = devices.count { it.deviceType.category == DeviceCategory.GUNSHOT_DETECTOR }
    /** Hits from opt-in packs (law enforcement, wearables, ...). Never mixed into Flock/Raven. */
    val otherCount: Int get() = totalCount - flockCount - ravenCount
    val showOther: Boolean get() = enabledPacks.isNotEmpty() || otherCount > 0
    val gpsLocked: Boolean get() = location.hasFix && location.isAvailable

    /** Categories present in this session, in enum order; drives the filter chips. */
    val presentCategories: List<DeviceCategory>
        get() = DeviceCategory.entries.filter { c -> devices.any { it.deviceType.category == c } }

    /** Chips are worth showing once there is more than one category to choose between. */
    val showFilter: Boolean get() = presentCategories.size > 1 || filter != null

    val visibleDevices: List<DetectedDevice>
        get() = filter?.let { f -> devices.filter { it.deviceType.category == f } } ?: devices

    val messages: List<StatusMessage>
        get() = buildList {
            scan.error?.let { add(StatusMessage("BLE: $it", isError = true, action = actionFor(scan.errorIssue))) }
            scan.warning?.let { add(StatusMessage("BLE: $it", isError = false, action = actionFor(scan.warningIssue))) }
            usb.error?.let { add(StatusMessage("USB: $it", isError = true)) }
            if (wifiApEnabled) {
                wifi.error?.let { add(StatusMessage("WiFi: $it", isError = true, action = actionFor(wifi.errorIssue))) }
                wifi.warning?.let { add(StatusMessage("WiFi: $it", isError = false, action = actionFor(wifi.warningIssue))) }
            }
            location.error?.let { add(StatusMessage("GPS: $it", isError = true, action = StatusAction.APP_SETTINGS)) }
        }

    private fun actionFor(issue: ScanIssue?): StatusAction? = when (issue) {
        ScanIssue.BLUETOOTH_OFF -> StatusAction.ENABLE_BLUETOOTH
        ScanIssue.LOCATION_OFF -> StatusAction.LOCATION_SETTINGS
        ScanIssue.WIFI_OFF -> StatusAction.WIFI_SETTINGS
        ScanIssue.PERMISSION_DENIED -> StatusAction.APP_SETTINGS
        else -> null
    }

    /** One sentence describing which radios are live, for the empty state. */
    val listeningSummary: String
        get() {
            val radios = buildList {
                if (scan.isScanning) add("phone Bluetooth")
                if (wifiApEnabled && wifi.isScanning) add("the WiFi access-point scan")
                if (usb.isConnected) add("the ESP32")
            }
            if (radios.isEmpty()) return "No radio is scanning right now. Fix the issue above and listening resumes automatically."
            val joined = when (radios.size) {
                1 -> radios[0]
                2 -> "${radios[0]} and ${radios[1]}"
                else -> radios.dropLast(1).joinToString(", ") + ", and " + radios.last()
            }
            val tail = if (usb.isConnected) "Flock cameras, Ravens and any enabled packs will appear here."
            else "Ravens and any enabled packs will appear here. Plug in the ESP32 over USB to detect Flock cameras."
            return "Listening with ${joined.replaceFirstChar { it.uppercase() }}. $tail"
        }
}

class DashboardViewModel(private val container: AppContainer) : ViewModel() {

    private val sessionManager = container.sessionManager
    private val filter = MutableStateFlow<DeviceCategory?>(null)

    init {
        // A filter belongs to the session it was chosen in; carrying "Raven only" into the next
        // session would hide every new Flock hit behind an empty list while the chirps play.
        viewModelScope.launch {
            sessionManager.currentSession.map { it?.id }.distinctUntilChanged().collect { filter.value = null }
        }
    }

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
        filter,
    ) { session, devices, r, (wifiEnabled, wifiStatus), f ->
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
            filter = f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardUiState())

    fun toggleSession() {
        if (sessionManager.isActive) sessionManager.stop()
        else sessionManager.start(container.settings.scanMode)
    }

    fun toggleAudio() = container.settings.setAudioAlerts(!container.settings.audioAlerts.value)

    fun connectUsb() = container.usbCompanion.connect()

    fun setFilter(category: DeviceCategory?) { filter.value = category }

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
