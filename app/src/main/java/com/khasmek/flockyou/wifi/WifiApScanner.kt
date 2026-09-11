package com.khasmek.flockyou.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DetectionSource
import com.khasmek.flockyou.detection.DetectionTable
import com.khasmek.flockyou.detection.DeviceClassifier
import com.khasmek.flockyou.detection.PackId
import com.khasmek.flockyou.location.GeoFix
import com.khasmek.flockyou.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class WifiScanStatus(
    val isScanning: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    /** Access points in the most recent result set (matched or not). */
    val lastResultCount: Int = 0,
    /** Result sets processed since the last [WifiApScanner.clear]. */
    val resultSets: Long = 0,
)

/**
 * Third detection source: the phone's own WiFi radio, reading the access points it can see and
 * matching each BSSID's OUI via [DeviceClassifier.classifyWifiAp].
 *
 * Android throttles app-requested scans hard (4 per 2 minutes in the foreground, 1 per 30 min in
 * the background) but runs its own scans every 15-30 s while the screen is on and publishes them
 * through [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION]. So this class mostly listens, and only
 * asks for a scan of its own every [REQUEST_INTERVAL_MS]. On a rooted device
 * `settings put global wifi_scan_throttle_enabled 0` lifts the throttle.
 *
 * Same hooks as the BLE scanner: [sessionId], [locationSource], [enabledPacks], a [DetectionTable].
 */
class WifiApScanner(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val wifiManager = appContext.getSystemService(WifiManager::class.java)

    val table = DetectionTable()
    val devices: StateFlow<List<DetectedDevice>> get() = table.devices
    val newDetections: SharedFlow<DetectedDevice> get() = table.newDetections

    private val _status = MutableStateFlow(WifiScanStatus())
    val status: StateFlow<WifiScanStatus> = _status.asStateFlow()

    @Volatile var locationSource: (() -> GeoFix?)? = null
    @Volatile var sessionId: String = ""
    @Volatile var enabledPacks: Set<PackId> = emptySet()

    private var receiverRegistered = false
    private var requestJob: Job? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) processResults()
        }
    }

    val isScanning: Boolean get() = _status.value.isScanning

    fun start() {
        if (receiverRegistered) return
        val wm = wifiManager
        if (wm == null) {
            _status.update { it.copy(isScanning = false, error = "This device has no WiFi") }
            return
        }
        if (!Permissions.allEssentialGranted(appContext)) {
            _status.update { it.copy(isScanning = false, error = "Location permission not granted") }
            return
        }
        ContextCompat.registerReceiver(
            appContext, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED, // system broadcast
        )
        receiverRegistered = true
        _status.update { it.copy(isScanning = true, error = null, warning = wifiWarning(wm)) }
        Log.i(TAG, "WiFi AP scan started")

        // Whatever the system already knows is worth a look immediately.
        processResults()

        requestJob = scope.launch {
            while (isActive) {
                requestScan(wm)
                delay(REQUEST_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (!receiverRegistered) return
        requestJob?.cancel()
        requestJob = null
        try { appContext.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) {}
        receiverRegistered = false
        _status.update { it.copy(isScanning = false) }
        Log.i(TAG, "WiFi AP scan stopped")
    }

    fun clear() {
        table.clear()
        _status.update { it.copy(lastResultCount = 0, resultSets = 0) }
    }

    // ------------------------------------------------------------------

    @Suppress("DEPRECATION") // startScan is deprecated but still the only way to nudge a scan
    private fun requestScan(wm: WifiManager) {
        val warning = wifiWarning(wm)
        if (warning != null) {
            _status.update { it.copy(warning = warning) }
            return
        }
        val accepted = try { wm.startScan() } catch (e: SecurityException) { false }
        _status.update {
            it.copy(warning = if (accepted) null else "Android throttled the WiFi scan; using system scans only")
        }
    }

    private fun wifiWarning(wm: WifiManager): String? = when {
        wm.isWifiEnabled -> null
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && wm.isScanAlwaysAvailable -> null // scanning allowed with WiFi "off"
        else -> "WiFi is off; turn it on (or allow scanning while off) for AP detection"
    }

    @SuppressLint("MissingPermission") // ACCESS_FINE_LOCATION checked in start()
    private fun processResults() {
        val wm = wifiManager ?: return
        val results: List<ScanResult> = try { wm.scanResults } catch (e: SecurityException) {
            _status.update { it.copy(error = "Location permission not granted") }
            return
        }
        _status.update { it.copy(lastResultCount = results.size, resultSets = it.resultSets + 1) }

        val now = System.currentTimeMillis()
        val fix = locationSource?.invoke()
        for (r in results) {
            val bssid = r.BSSID ?: continue
            val classification = DeviceClassifier.classifyWifiAp(bssid, enabledPacks) ?: continue
            val mac = DetectionTable.normalizeMac(bssid)
            val ssid = r.ssidOrNull()
            val channel = WifiChannels.fromFrequencyMhz(r.frequency)

            val (stored, isNew) = table.upsert(
                macAddress = mac,
                create = {
                    DetectedDevice(
                        sessionId = sessionId,
                        macAddress = mac,
                        source = DetectionSource.PHONE_WIFI,
                        deviceName = ssid,
                        detectionMethod = classification.method,
                        deviceType = classification.deviceType,
                        confidence = classification.confidence,
                        matchedOn = classification.matchedOn,
                        channel = channel,
                        rssi = r.level,
                        latitude = fix?.latitude,
                        longitude = fix?.longitude,
                        accuracyMeters = fix?.accuracyMeters,
                        firstSeen = now,
                        lastSeen = now,
                        sightings = 1,
                    )
                },
                merge = { existing ->
                    existing.copy(
                        deviceName = ssid ?: existing.deviceName,
                        channel = channel ?: existing.channel,
                        rssi = r.level,
                        lastSeen = now,
                        sightings = existing.sightings + 1,
                        latitude = fix?.latitude ?: existing.latitude,
                        longitude = fix?.longitude ?: existing.longitude,
                        accuracyMeters = if (fix != null) fix.accuracyMeters else existing.accuracyMeters,
                    )
                },
            )
            if (isNew) {
                Log.i(TAG, "DETECTED AP ${stored.macAddress} \"${stored.displayName}\" rssi=${stored.rssi} ch=$channel " +
                    "[${classification.method.wireName} on ${classification.matchedOn}] ${stored.deviceType.label}")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.ssidOrNull(): String? {
        val s = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')
        } else {
            SSID
        }
        return s?.takeIf { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "FlockYou/WifiAp"
        /** Stays under Android's 4-per-2-minutes foreground throttle with a little margin. */
        private const val REQUEST_INTERVAL_MS = 35_000L
    }
}

/** Frequency (MHz) to 802.11 channel number. Pure. */
object WifiChannels {
    fun fromFrequencyMhz(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 -> (mhz - 2407) / 5
        mhz in 5170..5895 -> (mhz - 5000) / 5
        mhz in 5955..7115 -> (mhz - 5950) / 5
        else -> null
    }
}
