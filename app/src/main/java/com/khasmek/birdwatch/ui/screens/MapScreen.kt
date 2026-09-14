package com.khasmek.birdwatch.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.Dash
import com.google.android.gms.maps.model.Gap
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.DeviceCard
import com.khasmek.birdwatch.ui.components.coords
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.util.Permissions

/**
 * What the user tapped on the map: a device's own marker, or a Remote ID operator marker. Keyed
 * by session + MAC because "All sessions" can hold the same MAC several times, and stored as a
 * string so it survives rotation via rememberSaveable.
 */
private data class MapSelection(val sessionId: String, val macAddress: String, val isOperator: Boolean) {
    fun matches(d: DetectedDevice) = d.sessionId == sessionId && d.macAddress == macAddress
    fun encode() = "$sessionId|$macAddress|${if (isOperator) 1 else 0}"

    companion object {
        fun of(d: DetectedDevice, isOperator: Boolean) = MapSelection(d.sessionId, d.macAddress, isOperator)
        fun decode(s: String?): MapSelection? {
            val parts = s?.split('|') ?: return null
            if (parts.size != 3) return null
            return MapSelection(parts[0], parts[1], parts[2] == "1")
        }
    }
}

private fun markerKey(d: DetectedDevice) = "${d.sessionId}|${d.macAddress}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = appViewModel { MapViewModel(it) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Inject the key + init the SDK before the first GoogleMap composable is created.
    LaunchedEffect(state.apiKey) { if (state.hasKey) viewModel.ensureMapsInitialized() }

    Scaffold(topBar = { TopAppBar(title = { Text("Map") }) }) { innerPadding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                !state.loaded -> Unit // encrypted settings still opening; avoid a "no key" flash
                !state.hasKey -> NoKeyPrompt(onOpenSettings)
                state.mapsInitFailed -> MessagePane(
                    title = "Maps SDK failed to initialise",
                    body = "Google Play services may be missing or out of date on this device.",
                )
                state.mapsReady -> MapContent(state, onScopeChange = viewModel::setScope)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapContent(state: MapUiState, onScopeChange: (MapScope) -> Unit) {
    val context = LocalContext.current
    val hasLocationPermission = remember { Permissions.allEssentialGranted(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3.5f) // continental US until we know better
    }
    // Both survive rotation and tab switches: the camera is saveable already, and re-framing
    // would yank the user away from wherever they had panned.
    var framed by rememberSaveable { mutableStateOf(false) }
    var selectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = remember(selectionKey) { MapSelection.decode(selectionKey) }

    // Frame the markers the first time we have any; otherwise centre on the phone's fix.
    val mappable = state.mappable
    LaunchedEffect(mappable.isNotEmpty(), state.fix != null) {
        if (framed) return@LaunchedEffect
        if (mappable.isNotEmpty()) {
            val b = LatLngBounds.builder()
            mappable.forEach {
                if (it.hasTargetLocation) b.include(LatLng(it.targetLatitude!!, it.targetLongitude!!))
                else if (it.hasLocation) b.include(LatLng(it.latitude!!, it.longitude!!))
                if (it.hasOperatorLocation) b.include(LatLng(it.operatorLatitude!!, it.operatorLongitude!!))
            }
            // Only a successful animation counts as framed; a failure (map not ready yet) leaves
            // it false so the next change of inputs tries again.
            runCatching {
                if (mappable.size == 1 && !mappable[0].hasOperatorLocation) {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(b.build().center, 15f))
                } else {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
                }
            }.onSuccess { framed = true }
        } else if (state.fix != null) {
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(state.fix.latitude, state.fix.longitude), 14f)
                )
            }
        }
    }

    // The sheet shows the live row for the tapped marker (RSSI, sightings, drone position keep
    // updating during a session). If the row is gone (session deleted), the sheet closes.
    val selectedDevice = selection?.let { sel -> state.devices.firstOrNull(sel::matches) }
    LaunchedEffect(selection, selectedDevice == null) { if (selection != null && selectedDevice == null) selectionKey = null }

    Column(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MapScope.entries.forEach { scope ->
                    FilterChip(
                        selected = state.scope == scope,
                        onClick = { onScopeChange(scope) },
                        label = { Text(scope.label) },
                        enabled = scope != MapScope.CURRENT_SESSION || state.sessionActive,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${mappable.size} on map" + if (state.unmappedCount > 0) " · ${state.unmappedCount} no GPS" else "",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (state.staleKey) {
            Text(
                "Map is still using the previously saved key. Restart the app to switch to the new one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocationPermission),
        ) {
            // Stable keys: the list is re-sorted on every re-sighting, and without keys Compose would
            // tear down and recreate marker nodes, dropping any tap in flight. Session + MAC, since
            // "All sessions" can contain the same MAC from several sessions.
            mappable.forEach { device ->
                key(markerKey(device)) {
                    DeviceMarker(device, onClick = { selectionKey = MapSelection.of(device, isOperator = false).encode() })
                    if (device.hasOperatorLocation) {
                        OperatorMarker(device, onClick = { selectionKey = MapSelection.of(device, isOperator = true).encode() })
                    }
                }
            }
        }
    }

    // Details live in our own bottom sheet rather than the SDK's info window, which renders a
    // detached ComposeView into a bitmap and comes out empty on current Compose versions.
    if (selection != null && selectedDevice != null) {
        val device = selectedDevice
        ModalBottomSheet(onDismissRequest = { selectionKey = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .navigationBarsPadding()
            ) {
                if (selection.isOperator && device.hasOperatorLocation) {
                    Text("Remote ID operator", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Position reported by the drone's own broadcast (System message), which may be the takeoff point rather than the pilot's live position.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    device.operatorId?.let {
                        Text("Operator ID $it", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                    Text(
                        coords(device.operatorLatitude!!, device.operatorLongitude!!),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Aircraft", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                } else {
                    Text(
                        if (device.hasTargetLocation) "Marker is the drone's self-reported position"
                        else "Marker is where the phone was when this was detected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                DeviceCard(device = device, now = System.currentTimeMillis(), initiallyExpanded = true)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun DeviceMarker(device: DetectedDevice, onClick: () -> Unit) {
    // A Remote ID drone tells us where IT is; everything else is placed where the phone was.
    // (A drone that reported only an operator position gets just the operator marker.)
    val position = when {
        device.hasTargetLocation -> LatLng(device.targetLatitude!!, device.targetLongitude!!)
        device.hasLocation -> LatLng(device.latitude!!, device.longitude!!)
        else -> return
    }
    val markerState = remember(device.macAddress, position) { MarkerState(position) }
    val hue = when (device.deviceType.category) {
        DeviceCategory.FLOCK_ALPR -> BitmapDescriptorFactory.HUE_ORANGE
        DeviceCategory.GUNSHOT_DETECTOR -> BitmapDescriptorFactory.HUE_VIOLET
        DeviceCategory.LAW_ENFORCEMENT -> BitmapDescriptorFactory.HUE_AZURE
        DeviceCategory.WEARABLE_CAMERA -> BitmapDescriptorFactory.HUE_CYAN
        DeviceCategory.DRONE -> BitmapDescriptorFactory.HUE_YELLOW
    }
    val icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) }

    Marker(
        state = markerState,
        title = "${device.deviceType.label} · ${device.displayName}",
        icon = icon,
        onClick = { onClick(); true }, // true = consume the tap; no SDK info window
    )
}

/**
 * Remote ID: the pilot's position from the System message, drawn as a rose marker with a dashed
 * line back to the aircraft so it is obvious which operator belongs to which drone.
 */
@Composable
private fun OperatorMarker(device: DetectedDevice, onClick: () -> Unit) {
    val operator = LatLng(device.operatorLatitude!!, device.operatorLongitude!!)
    val markerState = remember(device.macAddress, operator) { MarkerState(operator) }
    val icon = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE) }
    val label = device.uasId ?: device.displayName

    if (device.hasTargetLocation) {
        Polyline(
            points = listOf(LatLng(device.targetLatitude!!, device.targetLongitude!!), operator),
            color = DetectionColors.Drone,
            width = 5f,
            pattern = listOf(Dash(24f), Gap(12f)),
            zIndex = 1f,
        )
    }
    Marker(
        state = markerState,
        title = "Operator · $label",
        icon = icon,
        onClick = { onClick(); true },
    )
}

@Composable
private fun NoKeyPrompt(onOpenSettings: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Map, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Map needs an API key", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            "Enter your Google Maps API key in Settings to enable the map view. " +
                "Detection, sessions and export all work without one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text("Open Settings") }
    }
}

@Composable
private fun MessagePane(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
