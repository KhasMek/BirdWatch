package com.khasmek.birdwatch.data

import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.khasmek.birdwatch.detection.BleScanner
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionTable
import com.khasmek.birdwatch.detection.ScanForegroundService
import com.khasmek.birdwatch.location.LocationProvider
import com.khasmek.birdwatch.usb.FirmwareLineParser.toDetectedDevice
import com.khasmek.birdwatch.usb.UsbCompanion
import com.khasmek.birdwatch.wifi.WifiApScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * Owns the scan-session lifecycle: one UUID per session, start/end timestamps, and persisting
 * detections from every source into Room while a session is running.
 *
 * Starting a session starts GPS updates, the phone BLE scanner, the ESP32 USB companion (which
 * connects if a device is attached, and auto-connects if one is plugged in later) and, when the
 * user has switched it on, the phone WiFi access-point scanner. Stopping flushes every in-memory
 * table, stops the radios and closes the session row.
 *
 * Persistence strategy, per source:
 *  - a brand-new MAC is written immediately (from the table's `newDetections`);
 *  - re-sightings (RSSI, lastSeen, GPS, count, tier upgrades) are batched every
 *    [RESIGHT_FLUSH_MS] via `sample` so a chatty beacon does not hammer the database.
 */
class SessionManager(
    context: Context,
    private val db: DetectionDatabase,
    private val settings: AppSettings,
    private val bleScanner: BleScanner,
    private val usbCompanion: UsbCompanion,
    private val wifiApScanner: WifiApScanner,
    private val locationProvider: LocationProvider,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val detectionDao get() = db.detectionDao()
    private val sessionDao get() = db.sessionDao()

    private val _currentSession = MutableStateFlow<ScanSession?>(null)
    val currentSession: StateFlow<ScanSession?> = _currentSession.asStateFlow()
    val isActive: Boolean get() = _currentSession.value != null

    private val mutex = Mutex()
    private var persistJob: Job? = null

    /** First sighting of every MAC from any source. Drives audio alerts. */
    val newDetections: Flow<DetectedDevice> =
        merge(bleScanner.table.newDetections, usbCompanion.table.newDetections, wifiApScanner.table.newDetections)

    init {
        // Any session left open by a crash/kill is closed now so it shows up in history.
        scope.launch { sessionDao.closeOpenSessions(System.currentTimeMillis()) }
        // The WiFi AP scan switch takes effect mid-session too.
        scope.launch {
            settings.wifiApScan.collect { enabled ->
                if (!isActive) return@collect
                if (enabled) wifiApScanner.start() else wifiApScanner.stop()
            }
        }
    }

    /**
     * Start a session from the UI. Goes through [ScanForegroundService] so the session survives
     * backgrounding; if Android refuses to start a foreground service (app not visible), the
     * session still starts in-process so nothing is silently lost.
     */
    fun start(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY) {
        if (isActive) return
        try {
            ScanForegroundService.start(appContext, scanMode)
        } catch (e: Exception) {
            Log.w(TAG, "Foreground service start refused; running session in-process", e)
            scope.launch { startSuspending(scanMode) }
        }
    }

    /** Stop the radios, flush, and close the session. The service observes this and stops itself. */
    fun stop() {
        scope.launch { stopSuspending() }
    }

    suspend fun startSuspending(scanMode: Int = ScanSettings.SCAN_MODE_LOW_LATENCY): ScanSession = mutex.withLock {
        _currentSession.value?.let { return it }

        val session = ScanSession(id = UUID.randomUUID().toString(), startedAt = System.currentTimeMillis())
        sessionDao.insert(session)

        val fixSource = { locationProvider.currentFix() }

        bleScanner.clear()
        bleScanner.sessionId = session.id
        bleScanner.locationSource = fixSource

        usbCompanion.clear()
        usbCompanion.sessionId = session.id
        usbCompanion.locationSource = fixSource
        usbCompanion.wantConnection = true

        wifiApScanner.clear()
        wifiApScanner.sessionId = session.id
        wifiApScanner.locationSource = fixSource

        locationProvider.start()
        bleScanner.start(scanMode)
        usbCompanion.connect()
        if (settings.wifiApScan.value) wifiApScanner.start()

        persistJob = scope.launch { persistLoop() }
        _currentSession.value = session
        Log.i(TAG, "Session ${session.id} started")
        session
    }

    suspend fun stopSuspending() = mutex.withLock {
        val session = _currentSession.value ?: return@withLock

        bleScanner.stop()
        usbCompanion.wantConnection = false
        usbCompanion.disconnect()
        wifiApScanner.stop()
        locationProvider.stop()
        persistJob?.cancel()
        persistJob = null

        // Final flush so the last few re-sightings land in the database.
        val snapshot = bleScanner.table.snapshot() + usbCompanion.table.snapshot() + wifiApScanner.table.snapshot()
        if (snapshot.isNotEmpty()) detectionDao.upsertAll(snapshot)

        sessionDao.update(session.copy(endedAt = System.currentTimeMillis()))
        _currentSession.value = null
        Log.i(TAG, "Session ${session.id} ended with ${snapshot.size} devices")
    }

    // ------------------------------------------------------------------
    // Queries for the UI
    // ------------------------------------------------------------------

    /** Devices of the running session from the database (empty when idle). Merges all sources. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeCurrentDevices(): Flow<List<DetectedDevice>> =
        currentSession.flatMapLatest { s ->
            if (s == null) flowOf(emptyList()) else detectionDao.observeBySession(s.id)
        }

    fun observeSessionDevices(sessionId: String): Flow<List<DetectedDevice>> =
        detectionDao.observeBySession(sessionId)

    val sessionSummaries: Flow<List<SessionSummary>> get() = sessionDao.observeSummaries()
    val previousSession: Flow<SessionSummary?> get() = sessionDao.observePrevious()

    suspend fun getSessionDevices(sessionId: String): List<DetectedDevice> = detectionDao.getBySession(sessionId)

    /** Outcome of pulling the ESP32's stored table into a new session. */
    data class ImportResult(val session: ScanSession, val imported: Int, val announced: Int)

    /**
     * Pull the ESP32's on-device detection table (memory or flash) into a brand-new, already-ended
     * session labelled as an import. Records carry no wall-clock time and no GPS (see
     * `SessionRecord.toDetectedDevice`), so they never merge into a live session.
     */
    suspend fun importFromEsp32(source: UsbCompanion.DumpSource): ImportResult {
        val dump = usbCompanion.dumpSession(source)
        val now = System.currentTimeMillis()
        val session = ScanSession(
            id = UUID.randomUUID().toString(),
            startedAt = now,
            endedAt = now,
            label = "ESP32 import (${source.label})",
        )
        val devices = dump.records.map { it.toDetectedDevice(session.id, now) }
            .distinctBy { it.macAddress } // defensive: the firmware table is unique by MAC already
        db.withTransaction {
            sessionDao.insert(session)
            if (devices.isNotEmpty()) detectionDao.upsertAll(devices)
        }
        Log.i(TAG, "Imported ${devices.size} devices from ESP32 ${source.label} into session ${session.id}")
        return ImportResult(session, devices.size, dump.announcedCount)
    }

    class AlreadyImportedException(val sessionId: String) : Exception("This session is already in the app")

    /**
     * Restore a session from one of the app's own JSON/CSV exports (parsed by [ExportReader]).
     * The original id, timestamps and GPS are kept; a session with the same id is refused so the
     * same file cannot be imported twice.
     */
    suspend fun importExported(parsed: ImportedSession): ImportedSession {
        if (sessionDao.getById(parsed.session.id) != null) throw AlreadyImportedException(parsed.session.id)
        val session = parsed.session.copy(label = parsed.session.label ?: "Imported (${parsed.format.label})")
        db.withTransaction {
            sessionDao.insert(session)
            if (parsed.devices.isNotEmpty()) detectionDao.upsertAll(parsed.devices)
        }
        Log.i(TAG, "Imported session ${session.id} (${parsed.devices.size} devices) from ${parsed.format.label}")
        return parsed.copy(session = session)
    }

    suspend fun deleteSession(sessionId: String) {
        if (_currentSession.value?.id == sessionId) stopSuspending()
        db.withTransaction {
            detectionDao.deleteBySession(sessionId)
            sessionDao.deleteById(sessionId)
        }
        Log.i(TAG, "Session $sessionId deleted")
    }

    // ------------------------------------------------------------------

    private suspend fun persistLoop() = coroutineScope {
        launch { persist(bleScanner.table) }
        launch { persist(usbCompanion.table) }
        launch { persist(wifiApScanner.table) }
    }

    @OptIn(FlowPreview::class)
    private suspend fun persist(table: DetectionTable) = coroutineScope {
        launch { table.newDetections.collect { detectionDao.upsert(it) } }
        launch {
            table.devices.sample(RESIGHT_FLUSH_MS).collect { devices ->
                if (devices.isNotEmpty()) detectionDao.upsertAll(devices)
            }
        }
    }

    companion object {
        private const val TAG = "BirdWatch/Session"
        private const val RESIGHT_FLUSH_MS = 2_000L
    }
}
