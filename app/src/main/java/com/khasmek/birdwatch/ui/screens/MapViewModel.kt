package com.khasmek.birdwatch.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.DeviceOverride
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionTable
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.location.GeoFix
import com.khasmek.birdwatch.util.MapsKeyInjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MapScope(val label: String) { CURRENT_SESSION("This session"), ALL_SESSIONS("All sessions") }

/** A coordinate without Google Maps types, so the ViewModel stays framework-free. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * One pin: every row for a MAC in the current scope (newest first) plus what the user has said
 * about that device. "All sessions" used to draw one pin per session row, so a camera driven
 * past three times showed three pins; now it is one pin at the corrected position if there is
 * one, otherwise where it was last seen.
 */
data class MapPin(val macAddress: String, val rows: List<DetectedDevice>, val override: DeviceOverride?) {
    val latest: DetectedDevice get() = rows.first()
    val category: DeviceCategory get() = latest.deviceType.category
    val alias: String? get() = override?.alias?.takeIf { it.isNotBlank() }
    val isHiddenByUser: Boolean get() = override?.hidden == true
    val isMoved: Boolean get() = override?.hasLocation == true
    val canEdit: Boolean get() = latest.hasStableIdentity

    /** Where the device's own marker goes: user correction, else the drone's reported spot, else where the phone was. */
    val position: GeoPoint? = when {
        override?.hasLocation == true -> GeoPoint(override.latitude!!, override.longitude!!)
        latest.hasTargetLocation -> GeoPoint(latest.targetLatitude!!, latest.targetLongitude!!)
        else -> rows.firstOrNull { it.hasLocation }?.let { GeoPoint(it.latitude!!, it.longitude!!) }
    }

    /** Remote ID operator / takeoff position from the newest row that has one. */
    val operatorPosition: GeoPoint? =
        rows.firstOrNull { it.hasOperatorLocation }?.let { GeoPoint(it.operatorLatitude!!, it.operatorLongitude!!) }

    val isLocated: Boolean get() = position != null || operatorPosition != null
    val sessionCount: Int get() = rows.map { it.sessionId }.distinct().size
    val totalSightings: Int get() = rows.sumOf { it.sightings }
    val firstSeen: Long get() = rows.minOf { it.firstSeen }
    val lastSeen: Long get() = rows.maxOf { it.lastSeen }
}

data class MapUiState(
    val loaded: Boolean = false,
    val apiKey: String? = null,
    val mapsReady: Boolean = false,
    val mapsInitFailed: Boolean = false,
    /** The SDK is still running on a previously saved key; the new one applies after a restart. */
    val staleKey: Boolean = false,
    val scope: MapScope = MapScope.ALL_SESSIONS,
    val devices: List<DetectedDevice> = emptyList(),
    val overrides: Map<String, DeviceOverride> = emptyMap(),
    /** Categories the user has switched off with the chips above the map. */
    val hidden: Set<DeviceCategory> = emptySet(),
    val sessionActive: Boolean = false,
    val fix: GeoFix? = null,
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()

    /** One pin per MAC in scope, newest row first within each. */
    val pins: List<MapPin> = devices
        .groupBy { DetectionTable.normalizeMac(it.macAddress) }
        .map { (mac, rows) -> MapPin(mac, rows.sortedByDescending { it.lastSeen }, overrides[mac]) }

    /** Pins that have somewhere to be drawn, before any filtering. */
    val located: List<MapPin> = pins.filter { it.isLocated }

    /** Pins the user hid individually (from the sheet). */
    val hiddenByUser: List<MapPin> = located.filter { it.isHiddenByUser }

    /** What is actually drawn. */
    val mappable: List<MapPin> = located.filter { !it.isHiddenByUser && it.category !in hidden }

    val unmappedCount: Int get() = pins.size - located.size
    val hiddenCount: Int get() = located.size - mappable.size

    /** Categories with at least one located pin, in enum order; drives the filter chips. */
    val presentCategories: List<DeviceCategory>
        get() = DeviceCategory.entries.filter { c -> located.any { it.category == c } }

    fun locatedCount(category: DeviceCategory): Int = located.count { it.category == category }
    fun pin(mac: String): MapPin? = mappable.firstOrNull { it.macAddress == mac }
}

class MapViewModel(private val container: AppContainer) : ViewModel() {

    private val secure = container.secureSettings
    private val sessionManager = container.sessionManager
    private val overrideDao = container.database.deviceOverrideDao()

    private val _scope = MutableStateFlow(MapScope.ALL_SESSIONS)
    private val _mapsReady = MutableStateFlow(MapsKeyInjector.isInitialized)
    private val _mapsInitFailed = MutableStateFlow(false)

    private val _messages = Channel<String>(Channel.BUFFERED)
    /** One-line outcomes of edits, for the screen to toast. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val devices = _scope.flatMapLatest { scope ->
        when (scope) {
            MapScope.CURRENT_SESSION -> sessionManager.observeCurrentDevices()
            MapScope.ALL_SESSIONS -> container.database.detectionDao().observeAll()
        }
    }

    private val mapsState = combine(_mapsReady, _mapsInitFailed, MapsKeyInjector.keyInUse) { ready, failed, inUse -> Triple(ready, failed, inUse) }

    init {
        // "This session" only makes sense while one is running; fall back when it ends.
        viewModelScope.launch {
            sessionManager.currentSession.collect { session ->
                if (session == null && _scope.value == MapScope.CURRENT_SESSION) _scope.value = MapScope.ALL_SESSIONS
            }
        }
    }

    val uiState: StateFlow<MapUiState> = combine(
        secure.isLoaded,
        secure.mapsApiKey,
        mapsState,
        _scope,
        devices,
    ) { loaded, userKey, (ready, failed, inUse), scope, devices ->
        val key = MapsKeyInjector.effectiveKey(userKey)
        MapUiState(
            loaded = loaded, apiKey = key, mapsReady = ready, mapsInitFailed = failed,
            staleKey = key != null && inUse != null && inUse != key,
            scope = scope, devices = devices,
        )
    }.combine(sessionManager.currentSession) { s, session -> s.copy(sessionActive = session != null) }
        .combine(container.locationProvider.state) { s, loc -> s.copy(fix = loc.fix) }
        .combine(container.settings.mapHiddenCategories) { s, hidden -> s.copy(hidden = hidden) }
        .combine(overrideDao.observeAll()) { s, overrides -> s.copy(overrides = overrides.associateBy { it.macAddress }) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Inject the effective key into the Maps SDK. Safe to call on every entry to the map screen. */
    fun ensureMapsInitialized() {
        val key = MapsKeyInjector.effectiveKey(secure.mapsApiKey.value) ?: return
        val ok = MapsKeyInjector.initialize(container.appContext, key)
        _mapsReady.value = ok
        _mapsInitFailed.value = !ok
    }

    fun setScope(scope: MapScope) { _scope.value = scope }

    /** Show or hide one category's markers. Persisted, so a category you always hide stays hidden. */
    fun toggleCategory(category: DeviceCategory) {
        val settings = container.settings
        settings.setMapCategoryHidden(category, hidden = category !in settings.mapHiddenCategories.value)
    }

    // ---- per-device edits (shared DeviceEditor; outcome goes to messages) ---------------------

    private val editor = container.deviceEditor

    /** Pin the device at [point]; every session that saw this MAC now draws it there. */
    fun setLocation(mac: String, point: GeoPoint) = run { editor.setLocation(mac, point.latitude, point.longitude) }
    fun clearLocation(mac: String) = run { editor.clearLocation(mac) }
    fun setAlias(mac: String, alias: String?) = run { editor.setAlias(mac, alias) }
    fun setHidden(mac: String, hidden: Boolean) = run { editor.setHidden(mac, hidden) }

    /** Remove this device's rows from [sessionIds] (the sessions its pin currently covers). */
    fun deleteDetections(mac: String, sessionIds: Collection<String>) = run { editor.deleteDetections(mac, sessionIds) }

    private fun run(block: suspend () -> String) {
        viewModelScope.launch { _messages.send(block()) }
    }
}
