package com.khasmek.flockyou.detection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.location.LocationManagerCompat
import com.khasmek.flockyou.location.GeoFix
import com.khasmek.flockyou.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Live status of the scanner, observed by the UI. */
data class ScanStatus(
    val isScanning: Boolean = false,
    val scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY,
    /** Fatal problem that stopped (or prevented) scanning. */
    val error: String? = null,
    /** Non-fatal condition that may suppress results, e.g. location services off. */
    val warning: String? = null,
    /** Total advertisements seen since the last [BleScanner.clear], matched or not. Proves the radio is alive. */
    val rawAdvertisements: Long = 0,
)

/**
 * Continuous BLE scanning on top of [BluetoothLeScanner]. Every [ScanResult] is converted to a
 * framework-free [BleAdvertisement] and run through [DeviceClassifier]. Matches land in a
 * [DetectionTable] keyed by MAC: a re-sighting updates RSSI, name (if newly available), lastSeen
 * and the sighting count, exactly like the firmware's `fyAddDetection()`.
 *
 * Thread-safety: [ScanCallback] runs on a binder thread; [DetectionTable] and the status
 * [MutableStateFlow] are both safe to touch from there.
 */
class BleScanner(context: Context) {

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    val table = DetectionTable()

    private val _status = MutableStateFlow(ScanStatus())
    val status: StateFlow<ScanStatus> = _status.asStateFlow()

    /** All unique matched devices, most recently seen first. */
    val devices: StateFlow<List<DetectedDevice>> get() = table.devices

    /** Emits once per MAC the first time it is matched. Drives audio alerts. */
    val newDetections: SharedFlow<DetectedDevice> get() = table.newDetections

    /** Set by SessionManager: returns the current GPS fix to stamp on detections, or null. */
    @Volatile
    var locationSource: (() -> GeoFix?)? = null

    /** Set by SessionManager: the active session id stamped on new detections. */
    @Volatile
    var sessionId: String = ""

    private val rawLock = Any()
    private var rawCount = 0L
    private var lastRawPublish = 0L
    private var activeCallback: ScanCallback? = null

    val isScanning: Boolean get() = _status.value.isScanning

    /** Start (or restart with a new mode) a continuous scan. Safe to call repeatedly. */
    fun start(scanMode: Int = _status.value.scanMode) {
        if (activeCallback != null) {
            if (scanMode == _status.value.scanMode) return
            stopInternal()
        }

        val preflight = preflightError()
        if (preflight != null) {
            Log.w(TAG, "Cannot start scan: $preflight")
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = preflight) }
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "BLE scanner unavailable") }
            return
        }

        val callback = ResultCallback()
        try {
            startScanChecked(scanner, buildSettings(scanMode), callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for BLE scan", e)
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "Bluetooth scan permission denied") }
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Bluetooth adapter not ready", e)
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "Bluetooth adapter not ready") }
            return
        }

        activeCallback = callback
        _status.update {
            it.copy(isScanning = true, scanMode = scanMode, error = null, warning = locationWarning())
        }
        Log.i(TAG, "Scan started (mode=${modeName(scanMode)})")
    }

    fun stop() {
        stopInternal()
        _status.update { it.copy(isScanning = false) }
        Log.i(TAG, "Scan stopped")
    }

    /** Change scan mode; restarts the scan if one is running. */
    fun setScanMode(scanMode: Int) {
        if (isScanning) start(scanMode) else _status.update { it.copy(scanMode = scanMode) }
    }

    /** Drop every detection and reset counters. Does not affect scanning state. */
    fun clear() {
        table.clear()
        synchronized(rawLock) { rawCount = 0 }
        _status.update { it.copy(rawAdvertisements = 0) }
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    @SuppressLint("MissingPermission") // caller-side permission check done in preflightError()
    private fun startScanChecked(scanner: BluetoothLeScanner, settings: ScanSettings, cb: ScanCallback) {
        // Unfiltered scan on purpose: OUI + name matching cannot be expressed as hardware filters.
        scanner.startScan(null, settings, cb)
    }

    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val cb = activeCallback ?: return
        activeCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan without permission", e)
        } catch (e: IllegalStateException) {
            // Adapter already off; nothing to stop.
        }
    }

    private fun preflightError(): String? {
        if (!Permissions.allEssentialGranted(appContext)) return "Bluetooth/location permissions not granted"
        val a = adapter ?: return "This device has no Bluetooth adapter"
        if (!a.isEnabled) return "Bluetooth is turned off"
        return null
    }

    private fun locationWarning(): String? {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return null
        return if (LocationManagerCompat.isLocationEnabled(lm)) null
        else "Location services are off; Android may withhold BLE scan results"
    }

    private fun buildSettings(scanMode: Int): ScanSettings {
        val b = ScanSettings.Builder()
            .setScanMode(scanMode)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && adapter?.isLeExtendedAdvertisingSupported == true) {
            // Receive both legacy and extended (BT 5) advertisements.
            b.setLegacy(false)
        }
        return b.build()
    }

    private inner class ResultCallback : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handle(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handle)

        override fun onScanFailed(errorCode: Int) {
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "scan already started"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "app registration failed"
                SCAN_FAILED_INTERNAL_ERROR -> "internal Bluetooth error"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "feature unsupported"
                5 -> "out of hardware resources"      // SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES (API 33)
                6 -> "scanning too frequently"        // SCAN_FAILED_SCANNING_TOO_FREQUENTLY (API 33)
                else -> "error code $errorCode"
            }
            Log.e(TAG, "Scan failed: $reason")
            activeCallback = null
            _status.update { it.copy(isScanning = false, error = "Scan failed: $reason") }
        }
    }

    private fun handle(result: ScanResult) {
        publishRawCount()

        val adv = result.toAdvertisement() ?: return
        val classification = DeviceClassifier.classify(adv) ?: return
        val now = System.currentTimeMillis()
        val fix = locationSource?.invoke()
        val mac = DetectionTable.normalizeMac(adv.macAddress)

        val (stored, isNew) = table.upsert(
            macAddress = mac,
            create = {
                DetectedDevice(
                    sessionId = sessionId,
                    macAddress = mac,
                    source = DetectionSource.BLE,
                    deviceName = adv.deviceName,
                    detectionMethod = classification.method,
                    deviceType = classification.deviceType,
                    confidence = classification.confidence,
                    matchedOn = classification.matchedOn,
                    ravenFirmware = classification.ravenFirmware,
                    rssi = result.rssi,
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
                    // Keep a name once we have one; a later nameless advert must not erase it.
                    deviceName = adv.deviceName?.takeIf { it.isNotBlank() } ?: existing.deviceName,
                    rssi = result.rssi,
                    lastSeen = now,
                    sightings = existing.sightings + 1,
                    // Refresh GPS on every re-sighting so movement is captured.
                    latitude = fix?.latitude ?: existing.latitude,
                    longitude = fix?.longitude ?: existing.longitude,
                    accuracyMeters = if (fix != null) fix.accuracyMeters else existing.accuracyMeters,
                )
            },
        )

        if (isNew) {
            Log.i(
                TAG, "DETECTED ${stored.macAddress} \"${stored.displayName}\" rssi=${stored.rssi} " +
                    "[${classification.method.wireName} on ${classification.matchedOn}]" +
                    (classification.ravenFirmware?.let { " fw=$it" } ?: "")
            )
        }
    }

    private fun publishRawCount() {
        val now = System.currentTimeMillis()
        val count: Long
        val publish: Boolean
        synchronized(rawLock) {
            count = ++rawCount
            publish = now - lastRawPublish >= RAW_PUBLISH_INTERVAL_MS
            if (publish) lastRawPublish = now
        }
        if (publish) _status.update { it.copy(rawAdvertisements = count) }
    }

    @SuppressLint("MissingPermission") // device.name needs BLUETOOTH_CONNECT; guarded by try/catch
    private fun ScanResult.toAdvertisement(): BleAdvertisement? {
        val mac = device?.address ?: return null
        val record = scanRecord
        val name = record?.deviceName?.takeIf { it.isNotBlank() }
            ?: try { device.name?.takeIf { it.isNotBlank() } } catch (_: SecurityException) { null }
        val mfrIds = record?.manufacturerSpecificData?.let { sa ->
            buildSet { for (i in 0 until sa.size()) add(sa.keyAt(i)) }
        } ?: emptySet()
        val uuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()
        return BleAdvertisement(
            macAddress = mac,
            deviceName = name,
            manufacturerIds = mfrIds,
            serviceUuids = uuids,
        )
    }

    private fun modeName(mode: Int) = when (mode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        else -> mode.toString()
    }

    companion object {
        private const val TAG = "FlockYou/BleScanner"
        private const val RAW_PUBLISH_INTERVAL_MS = 500L
    }
}
