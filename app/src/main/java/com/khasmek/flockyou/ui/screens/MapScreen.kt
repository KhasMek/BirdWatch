package com.khasmek.flockyou.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerInfoWindowContent
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DeviceType
import com.khasmek.flockyou.ui.appViewModel
import com.khasmek.flockyou.util.Permissions
import com.khasmek.flockyou.util.TimeFormat

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

@Composable
private fun MapContent(state: MapUiState, onScopeChange: (MapScope) -> Unit) {
    val context = LocalContext.current
    val hasLocationPermission = remember { Permissions.allEssentialGranted(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3.5f) // continental US until we know better
    }
    var framed by remember { mutableStateOf(false) }

    // Frame the markers the first time we have any; otherwise centre on the phone's fix.
    val mappable = state.mappable
    LaunchedEffect(mappable.isNotEmpty(), state.fix != null) {
        if (framed) return@LaunchedEffect
        if (mappable.isNotEmpty()) {
            val b = LatLngBounds.builder()
            mappable.forEach { b.include(LatLng(it.latitude!!, it.longitude!!)) }
            runCatching {
                if (mappable.size == 1) {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(b.build().center, 15f))
                } else {
                    cameraPositionState.animate(CameraUpdateFactory.newLatLngBounds(b.build(), 120))
                }
            }
            framed = true
        } else if (state.fix != null) {
            runCatching {
                cameraPositionState.animate(
                    CameraUpdateFactory.newLatLngZoom(LatLng(state.fix.latitude, state.fix.longitude), 14f)
                )
            }
        }
    }

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

        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
            uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocationPermission),
        ) {
            mappable.forEach { device -> DeviceMarker(device) }
        }
    }
}

@Composable
private fun DeviceMarker(device: DetectedDevice) {
    val position = LatLng(device.latitude!!, device.longitude!!)
    val markerState = remember(device.macAddress, position) { MarkerState(position) }
    val hue = when (device.deviceType) {
        DeviceType.FLOCK -> BitmapDescriptorFactory.HUE_ORANGE
        DeviceType.RAVEN -> BitmapDescriptorFactory.HUE_VIOLET
        DeviceType.SOUNDTHINKING -> BitmapDescriptorFactory.HUE_MAGENTA
    }
    val icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) }

    MarkerInfoWindowContent(
        state = markerState,
        title = "${device.deviceType.label} · ${device.displayName}",
        icon = icon,
    ) {
        Column(Modifier.padding(4.dp)) {
            Text(
                "${device.deviceType.label} · ${device.displayName}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(device.macAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Text(
                device.detectionMethod.label + (device.tier?.let { " · T$it" } ?: "") +
                    (device.ravenFirmware?.let { " · fw $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "RSSI ${device.rssi} dBm · ${device.sightings}x · ${device.source.label}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "First ${TimeFormat.dateTime(device.firstSeen)} · last ${TimeFormat.clock(device.lastSeen)}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
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
        Spacer(Modifier.width(1.dp))
    }
}
