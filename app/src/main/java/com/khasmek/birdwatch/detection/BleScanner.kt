package com.khasmek.birdwatch.detection

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.khasmek.birdwatch.location.GeoFix
import com.khasmek.birdwatch.util.Permissions
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
    val errorIssue: ScanIssue? = null,
    /** Non-fatal condition that may suppress results, e.g. location services off. */
    val warning: String? = null,
    val warningIssue: ScanIssue? = null,
    /** Total advertisements seen since the last [BleScanner.clear], matched or not. Proves the radio is alive. */
    val rawAdvertisements: Long = 0,
)

/**
 * Continuous BLE scanning on top of [BluetoothLeScanner]. Every [ScanResult] is converted to a
 * framework-free [BleAdvertisement] and run through [DeviceClassifier]. Matches land in a
 * [DetectionTable] keyed by MAC: a re-sighting updates RSSI, name (if newly available), lastSeen
 * and the sighting count, exactly like the firmware's `fyAddDetection()`.
 *
 * If [start] is called while Bluetooth is off, the scanner remembers that it *wants* to scan and
 * starts by itself the moment the adapter comes on; the location-services warning likewise
 * clears itself when the user flips location back on.
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

    /** Opt-in signature packs to consult after the Core heuristics. Mirrors AppSettings.enabledPacks. */
    @Volatile
    var enabledPacks: Set<PackId> = emptySet()

    private val rawLock = Any()
    private var rawCount = 0L
    private var lastRawPublish = 0L

    /**
     * Guards every start/stop transition. Callers arrive on the main thread (lifecycle observer,
     * broadcast receiver), a Default dispatcher (session stop) and the binder thread
     * ([ScanCallback.onScanFailed]); without this, a stop racing a mode switch could register a
     * fresh callback after the old one was cleared and leave the radio scanning with no session.
     */
    private val lock = Any()
    private var activeCallback: ScanCallback? = null

    /** True between start() and stop(): the session wants scanning even if the radio is currently off. */
    @Volatile
    private var wantScanning = false

    /**
     * Restart budget. The Bluetooth stack refuses `startScan` once an app has stopped five scans
     * inside 30 s ("scanning too frequently"), and a refused start is reported only through
     * [ScanCallback.onScanFailed]. Every mode switch is a stop + start, so a few quick
     * foreground/background transitions would otherwise silence the radio for the rest of the
     * session. [ScanRestartBudget] tracks our own stops so a restart is deferred until it is safe.
     */
    private val restartBudget = ScanRestartBudget()
    private val handler = Handler(Looper.getMainLooper())
    private val retryRunnable = Runnable { retryStart() }
    /** Mode the caller asked for while a restart was deferred; applied by [retryStart]. */
    private var pendingMode: Int? = null
    @Volatile
    private var retryAttempt = 0

    val isScanning: Boolean get() = _status.value.isScanning

    private val systemReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    synchronized(lock) {
                        if (state == BluetoothAdapter.STATE_ON && wantScanning && activeCallback == null) {
                            Log.i(TAG, "Bluetooth came on; resuming scan")
                            start(pendingMode ?: _status.value.scanMode)
                        } else if (state == BluetoothAdapter.STATE_OFF && activeCallback != null) {
                            activeCallback = null // the stack already tore the scan down; it does not count as our stop
                            _status.update { it.copy(isScanning = false, error = "Bluetooth is turned off", errorIssue = ScanIssue.BLUETOOTH_OFF) }
                        }
                    }
                }
                LocationManager.MODE_CHANGED_ACTION -> if (activeCallback != null) {
                    val (warning, issue) = locationWarning()
                    _status.update { it.copy(warning = warning, warningIssue = issue) }
                }
            }
        }
    }

    init {
        ContextCompat.registerReceiver(
            appContext, systemReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(LocationManager.MODE_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_EXPORTED, // system broadcasts
        )
    }

    /**
     * Start (or restart with a new mode) a continuous scan. Safe to call repeatedly. A mode change
     * that would exceed the restart budget is applied later, automatically.
     */
    fun start(scanMode: Int = _status.value.scanMode): Unit = synchronized(lock) {
        wantScanning = true
        if (activeCallback != null) {
            if (scanMode == _status.value.scanMode) {
                pendingMode = null
                return
            }
            val wait = restartBudget.msUntilRestartAllowed(System.currentTimeMillis())
            if (wait > 0) {
                pendingMode = scanMode
                Log.i(TAG, "Deferring switch to ${modeName(scanMode)} by ${wait} ms (restart budget)")
                schedule(wait)
                return
            }
            stopInternal()
        }
        pendingMode = null
        startInternal(scanMode)
    }

    fun stop(): Unit = synchronized(lock) {
        wantScanning = false
        pendingMode = null
        retryAttempt = 0
        handler.removeCallbacks(retryRunnable)
        stopInternal()
        _status.update { it.copy(isScanning = false, error = null, errorIssue = null) }
        Log.i(TAG, "Scan stopped")
    }

    /** Change scan mode; restarts the scan if one is running (subject to the restart budget). */
    fun setScanMode(scanMode: Int): Unit = synchronized(lock) {
        if (activeCallback != null || (wantScanning && pendingMode != null)) start(scanMode)
        else _status.update { it.copy(scanMode = scanMode) }
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

    /** Must hold [lock]. Runs preflight, registers a fresh callback, publishes status. */
    private fun startInternal(scanMode: Int) {
        preflight()?.let { (issue, message) ->
            Log.w(TAG, "Cannot start scan: $message")
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = message, errorIssue = issue) }
            return
        }

        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "BLE scanner unavailable", errorIssue = ScanIssue.NO_ADAPTER) }
            return
        }

        val callback = ResultCallback()
        try {
            startScanChecked(scanner, buildSettings(scanMode), callback)
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing permission for BLE scan", e)
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "Bluetooth scan permission denied", errorIssue = ScanIssue.PERMISSION_DENIED) }
            return
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Bluetooth adapter not ready", e)
            _status.update { it.copy(isScanning = false, scanMode = scanMode, error = "Bluetooth adapter not ready", errorIssue = ScanIssue.BLUETOOTH_OFF) }
            return
        }

        activeCallback = callback
        handler.removeCallbacks(retryRunnable)
        val (warning, warningIssue) = locationWarning()
        _status.update {
            it.copy(isScanning = true, scanMode = scanMode, error = null, errorIssue = null, warning = warning, warningIssue = warningIssue)
        }
        Log.i(TAG, "Scan started (mode=${modeName(scanMode)})")
    }

    @SuppressLint("MissingPermission") // caller-side permission check done in preflight()
    private fun startScanChecked(scanner: BluetoothLeScanner, settings: ScanSettings, cb: ScanCallback) {
        // Unfiltered scan on purpose: OUI + name matching cannot be expressed as hardware filters.
        scanner.startScan(null, settings, cb)
    }

    /** Must hold [lock]. Unregisters the callback and records the stop against the restart budget. */
    @SuppressLint("MissingPermission")
    private fun stopInternal() {
        val cb = activeCallback ?: return
        activeCallback = null
        restartBudget.recordStop(System.currentTimeMillis())
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (e: SecurityException) {
            Log.w(TAG, "stopScan without permission", e)
        } catch (e: IllegalStateException) {
            // Adapter already off; nothing to stop.
        }
    }

    // ---- deferred restarts -----------------------------------------------------------------

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(retryRunnable)
        handler.postDelayed(retryRunnable, delayMs)
    }

    /** Runs on the main thread: apply a deferred mode switch or retry after a failed start. */
    private fun retryStart(): Unit = synchronized(lock) {
        if (!wantScanning) return
        val mode = pendingMode ?: _status.value.scanMode
        if (activeCallback != null && pendingMode == null) return // nothing to do; a start succeeded meanwhile
        Log.i(TAG, "Retrying scan start (mode=${modeName(mode)}, attempt=$retryAttempt)")
        start(mode)
    }

    /** Must hold [lock]. Called when a start failed asynchronously and the session still wants to scan. */
    private fun scheduleRetryAfterFailure() {
        retryAttempt++
        val backoff = (RETRY_BASE_MS shl (retryAttempt - 1).coerceAtMost(4)).coerceAtMost(RETRY_MAX_MS)
        val delay = maxOf(backoff, restartBudget.msUntilRestartAllowed(System.currentTimeMillis()))
        Log.i(TAG, "Scan start will be retried in $delay ms")
        schedule(delay)
    }

    private fun preflight(): Pair<ScanIssue, String>? {
        if (!Permissions.allEssentialGranted(appContext)) return ScanIssue.PERMISSION_DENIED to "Bluetooth/location permissions not granted"
        val a = adapter ?: return ScanIssue.NO_ADAPTER to "This device has no Bluetooth adapter"
        if (!a.isEnabled) return ScanIssue.BLUETOOTH_OFF to "Bluetooth is turned off"
        return null
    }

    private fun locationWarning(): Pair<String?, ScanIssue?> {
        val lm = appContext.getSystemService(LocationManager::class.java) ?: return null to null
        return if (LocationManagerCompat.isLocationEnabled(lm)) null to null
        else "Location services are off; Android withholds BLE scan results" to ScanIssue.LOCATION_OFF
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
            synchronized(lock) {
                // Only the callback that failed may tear the state down; a stale one must not
                // clobber a scan that was restarted since.
                if (activeCallback !== this) return
                activeCallback = null
                _status.update { it.copy(isScanning = false, error = "Scan failed: $reason", errorIssue = ScanIssue.SCAN_FAILED) }
                if (wantScanning) scheduleRetryAfterFailure()
            }
        }
    }

    private fun handle(result: ScanResult) {
        publishRawCount()
        if (retryAttempt != 0) retryAttempt = 0 // results are flowing again

        val adv = result.toAdvertisement() ?: return
        val classification = DeviceClassifier.classify(adv, enabledPacks) ?: return
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
                ).withRemoteId(classification.remoteId)
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
                ).withRemoteId(classification.remoteId) // a drone's position updates every second
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
        val serviceData = record?.serviceData?.entries?.associate { (uuid, bytes) -> uuid.uuid.toString() to bytes } ?: emptyMap()
        return BleAdvertisement(
            macAddress = mac,
            deviceName = name,
            manufacturerIds = mfrIds,
            serviceUuids = uuids,
            serviceData = serviceData,
        )
    }

    private fun modeName(mode: Int) = when (mode) {
        ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
        ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
        ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
        else -> mode.toString()
    }

    companion object {
        private const val TAG = "BirdWatch/BleScanner"
        private const val RAW_PUBLISH_INTERVAL_MS = 500L
        private const val RETRY_BASE_MS = 2_000L
        private const val RETRY_MAX_MS = 60_000L
    }
}
