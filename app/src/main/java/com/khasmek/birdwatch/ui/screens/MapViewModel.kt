package com.khasmek.birdwatch.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.location.GeoFix
import com.khasmek.birdwatch.util.MapsKeyInjector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class MapScope(val label: String) { CURRENT_SESSION("This session"), ALL_SESSIONS("All sessions") }

data class MapUiState(
    val loaded: Boolean = false,
    val apiKey: String? = null,
    val mapsReady: Boolean = false,
    val mapsInitFailed: Boolean = false,
    /** The SDK is still running on a previously saved key; the new one applies after a restart. */
    val staleKey: Boolean = false,
    val scope: MapScope = MapScope.ALL_SESSIONS,
    val devices: List<DetectedDevice> = emptyList(),
    val sessionActive: Boolean = false,
    val fix: GeoFix? = null,
) {
    val hasKey: Boolean get() = !apiKey.isNullOrBlank()
    val mappable: List<DetectedDevice> get() = devices.filter { it.hasLocation || it.hasTargetLocation || it.hasOperatorLocation }
    val unmappedCount: Int get() = devices.size - mappable.size
}

class MapViewModel(private val container: AppContainer) : ViewModel() {

    private val secure = container.secureSettings
    private val sessionManager = container.sessionManager

    private val _scope = MutableStateFlow(MapScope.ALL_SESSIONS)
    private val _mapsReady = MutableStateFlow(MapsKeyInjector.isInitialized)
    private val _mapsInitFailed = MutableStateFlow(false)

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
    ) { loaded, key, (ready, failed, inUse), scope, devices ->
        MapUiState(
            loaded = loaded, apiKey = key, mapsReady = ready, mapsInitFailed = failed,
            staleKey = key != null && inUse != null && inUse != key,
            scope = scope, devices = devices,
        )
    }.combine(sessionManager.currentSession) { s, session -> s.copy(sessionActive = session != null) }
        .combine(container.locationProvider.state) { s, loc -> s.copy(fix = loc.fix) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapUiState())

    /** Inject the stored key into the Maps SDK. Safe to call on every entry to the map screen. */
    fun ensureMapsInitialized() {
        val key = secure.mapsApiKey.value ?: return
        val ok = MapsKeyInjector.initialize(container.appContext, key)
        _mapsReady.value = ok
        _mapsInitFailed.value = !ok
    }

    fun setScope(scope: MapScope) { _scope.value = scope }
}
