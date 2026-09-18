package com.khasmek.birdwatch.ui.screens

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.DeviceEditDialog
import com.khasmek.birdwatch.ui.components.DeleteDeviceDialog
import com.khasmek.birdwatch.ui.components.DeviceCard
import com.khasmek.birdwatch.ui.components.categoryIcon
import com.khasmek.birdwatch.ui.components.coords
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.util.Permissions
import com.khasmek.birdwatch.util.TimeFormat

/**
 * What the user tapped on the map: a device's own marker, or a Remote ID operator marker.
 * Stored as a string so it survives rotation via rememberSaveable.
 */
private data class MapSelection(val macAddress: String, val isOperator: Boolean) {
    fun encode() = "$macAddress|${if (isOperator) 1 else 0}"

    companion object {
        fun decode(s: String?): MapSelection? {
            val parts = s?.split('|') ?: return null
            if (parts.size != 2) return null
            return MapSelection(parts[0], parts[1] == "1")
        }
    }
}

private fun GeoPoint.toLatLng() = LatLng(latitude, longitude)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    onOpenSettings: () -> Unit,
    viewModel: MapViewModel = appViewModel { MapViewModel(it) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Inject the key + init the SDK before the first GoogleMap composable is created.
    LaunchedEffect(state.apiKey) { if (state.hasKey) viewModel.ensureMapsInitialized() }
    LaunchedEffect(Unit) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

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
                state.mapsReady -> MapContent(state, viewModel)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapContent(state: MapUiState, viewModel: MapViewModel) {
    val context = LocalContext.current
    val hasLocationPermission = remember { Permissions.allEssentialGranted(context) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(LatLng(39.5, -98.35), 3.5f) // continental US until we know better
    }
    // All of these survive rotation and tab switches: the camera is saveable already, and
    // re-framing would yank the user away from wherever they had panned.
    var framed by rememberSaveable { mutableStateOf(false) }
    var selectionKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selection = remember(selectionKey) { MapSelection.decode(selectionKey) }
    /** MAC of the pin being moved with the crosshair, or null when not in move mode. */
    var movingMac by rememberSaveable { mutableStateOf<String?>(null) }
    var aliasMac by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteMac by rememberSaveable { mutableStateOf<String?>(null) }
    var showHiddenList by rememberSaveable { mutableStateOf(false) }

    // Frame the markers the first time we have any; otherwise centre on the phone's fix.
    val mappable = state.mappable
    LaunchedEffect(mappable.isNotEmpty(), state.fix != null) {
        if (framed) return@LaunchedEffect
        if (mappable.isNotEmpty()) {
            val b = LatLngBounds.builder()
            mappable.forEach { pin ->
                pin.position?.let { b.include(it.toLatLng()) }
                pin.operatorPosition?.let { b.include(it.toLatLng()) }
            }
            // Only a successful animation counts as framed; a failure (map not ready yet) leaves
            // it false so the next change of inputs tries again.
            runCatching {
                if (mappable.size == 1 && mappable[0].operatorPosition == null) {
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

    // The sheet shows the live pin for the tapped marker (RSSI, sightings, drone position keep
    // updating during a session). If the pin is gone (deleted, hidden, filtered), the sheet closes.
    val selectedPin = selection?.let { state.pin(it.macAddress) }
    LaunchedEffect(selection, selectedPin == null) { if (selection != null && selectedPin == null) selectionKey = null }

    // Move mode: start by centring on the pin's current position, zoomed in enough to be precise.
    val movingPin = movingMac?.let { mac -> state.pins.firstOrNull { it.macAddress == mac } }
    LaunchedEffect(movingMac) {
        val target = movingPin?.position ?: return@LaunchedEffect
        runCatching { cameraPositionState.animate(CameraUpdateFactory.newLatLngZoom(target.toLatLng(), 18f)) }
    }
    if (movingMac != null && movingPin == null) movingMac = null

    Column(Modifier.fillMaxSize()) {
        MapHeader(state, viewModel, onShowHidden = { showHiddenList = true })

        Box(Modifier.fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(isMyLocationEnabled = hasLocationPermission),
                uiSettings = MapUiSettings(zoomControlsEnabled = false, myLocationButtonEnabled = hasLocationPermission),
            ) {
                // Stable keys: the list is re-sorted on every re-sighting, and without keys Compose
                // would tear down and recreate marker nodes, dropping any tap in flight.
                mappable.forEach { pin ->
                    key(pin.macAddress) {
                        DeviceMarker(pin, onClick = { if (movingMac == null) selectionKey = MapSelection(pin.macAddress, isOperator = false).encode() })
                        if (pin.operatorPosition != null) {
                            OperatorMarker(pin, onClick = { if (movingMac == null) selectionKey = MapSelection(pin.macAddress, isOperator = true).encode() })
                        }
                    }
                }
            }

            if (movingPin != null) {
                MoveOverlay(
                    pin = movingPin,
                    hasFix = state.fix != null,
                    onUseMyLocation = {
                        state.fix?.let { f ->
                            viewModel.setLocation(movingPin.macAddress, GeoPoint(f.latitude, f.longitude))
                            movingMac = null
                        }
                    },
                    onSave = {
                        val t = cameraPositionState.position.target
                        viewModel.setLocation(movingPin.macAddress, GeoPoint(t.latitude, t.longitude))
                        movingMac = null
                    },
                    onCancel = { movingMac = null },
                )
            }
        }
    }

    // Details live in our own bottom sheet rather than the SDK's info window, which renders a
    // detached ComposeView into a bitmap and comes out empty on current Compose versions.
    if (selection != null && selectedPin != null && movingMac == null) {
        PinSheet(
            pin = selectedPin,
            isOperator = selection.isOperator,
            onDismiss = { selectionKey = null },
            onMove = { movingMac = selectedPin.macAddress; selectionKey = null },
            onResetPin = { viewModel.clearLocation(selectedPin.macAddress) },
            onAlias = { aliasMac = selectedPin.macAddress },
            onHide = { viewModel.setHidden(selectedPin.macAddress, true); selectionKey = null },
            onDelete = { deleteMac = selectedPin.macAddress },
        )
    }

    aliasMac?.let { mac ->
        val pin = state.pins.firstOrNull { it.macAddress == mac }
        DeviceEditDialog(
            currentAlias = pin?.alias,
            currentNotes = pin?.override?.notes,
            detectedName = pin?.latest?.displayName ?: mac,
            onDismiss = { aliasMac = null },
            onSave = { alias, notes -> viewModel.setDetails(mac, alias, notes); aliasMac = null },
        )
    }

    deleteMac?.let { mac ->
        val pin = state.pins.firstOrNull { it.macAddress == mac }
        if (pin == null) {
            deleteMac = null
        } else {
            DeleteDeviceDialog(
                name = pin.alias ?: pin.latest.displayName,
                macAddress = pin.macAddress,
                rowCount = pin.rows.size,
                sessionCount = pin.sessionCount,
                sessionActive = state.sessionActive,
                onDismiss = { deleteMac = null },
                onConfirm = {
                    viewModel.deleteDetections(mac, pin.rows.map { it.sessionId }.distinct())
                    deleteMac = null
                    selectionKey = null
                },
            )
        }
    }

    if (showHiddenList) {
        HiddenDevicesDialog(
            pins = state.hiddenByUser,
            onShow = { viewModel.setHidden(it, false) },
            onDismiss = { showHiddenList = false },
        )
    }
}

@Composable
private fun MapHeader(state: MapUiState, viewModel: MapViewModel, onShowHidden: () -> Unit) {
    val mappable = state.mappable
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        Column {
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
                        onClick = { viewModel.setScope(scope) },
                        label = { Text(scope.label) },
                        enabled = scope != MapScope.CURRENT_SESSION || state.sessionActive,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = buildString {
                        append("${mappable.size} on map")
                        if (state.hiddenCount > 0) append(" · ${state.hiddenCount} hidden")
                        if (state.unmappedCount > 0) append(" · ${state.unmappedCount} no GPS")
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                )
            }
            // Category toggles, one per category that has something to draw. Multi-select:
            // a highlighted chip is shown on the map, a plain one is hidden.
            val categories = state.presentCategories
            val hiddenByUser = state.hiddenByUser.size
            if (categories.size > 1 || hiddenByUser > 0) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp, end = 12.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (categories.size > 1) {
                        categories.forEach { c ->
                            val shown = c !in state.hidden
                            FilterChip(
                                selected = shown,
                                onClick = { viewModel.toggleCategory(c) },
                                label = { Text("${c.shortLabel} ${state.locatedCount(c)}") },
                                leadingIcon = {
                                    Icon(
                                        if (shown) categoryIcon(c) else Icons.Default.VisibilityOff,
                                        contentDescription = null,
                                        tint = if (shown) DetectionColors.textForCategory(c) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.height(16.dp),
                                    )
                                },
                            )
                        }
                    }
                    if (hiddenByUser > 0) {
                        TextButton(onClick = onShowHidden) {
                            Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.height(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("$hiddenByUser hidden by you")
                        }
                    }
                }
            }
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
}

/**
 * Move mode: a fixed crosshair over the map centre; the user pans the map underneath it. More
 * precise than dragging the marker, where a finger hides the pin and fights the pan gesture.
 */
@Composable
private fun MoveOverlay(
    pin: MapPin,
    hasFix: Boolean,
    onUseMyLocation: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        Icon(
            Icons.Default.GpsFixed,
            contentDescription = "Crosshair",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier
                .align(Alignment.Center)
                .size(36.dp),
        )
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Move pin: ${pin.alias ?: pin.latest.displayName}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Pan the map until the crosshair sits on the device, then save. Every session that saw this device will use the new spot.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onUseMyLocation, enabled = hasFix) {
                        Icon(Icons.Default.MyLocation, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("My location")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = onSave) { Text("Save") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinSheet(
    pin: MapPin,
    isOperator: Boolean,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onResetPin: () -> Unit,
    onAlias: () -> Unit,
    onHide: () -> Unit,
    onDelete: () -> Unit,
) {
    val device = pin.latest
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .navigationBarsPadding()
        ) {
            if (isOperator && pin.operatorPosition != null) {
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
                    coords(pin.operatorPosition.latitude, pin.operatorPosition.longitude),
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(12.dp))
                Text("Aircraft", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
            } else {
                Text(
                    when {
                        pin.isMoved -> "Pin placed by you" + pin.override?.updatedAt?.takeIf { it > 0 }?.let { " on ${TimeFormat.dateTime(it)}" }.orEmpty()
                        device.hasTargetLocation -> "Marker is the drone's self-reported position"
                        else -> "Marker is where the phone was when this was detected"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pin.sessionCount > 1) {
                    Text(
                        "Seen in ${pin.sessionCount} sessions, ${pin.totalSightings} sightings, " +
                            "${TimeFormat.dateTime(pin.firstSeen)} to ${TimeFormat.dateTime(pin.lastSeen)}. Showing the latest.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            DeviceCard(device = device, now = System.currentTimeMillis(), initiallyExpanded = true, override = pin.override)

            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pin.canEdit) {
                    OutlinedButton(onClick = onMove) {
                        Icon(Icons.Default.EditLocationAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Move pin")
                    }
                    if (pin.isMoved) {
                        OutlinedButton(onClick = onResetPin) { Text("Reset pin") }
                    }
                    OutlinedButton(onClick = onAlias) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (pin.alias == null && pin.override?.notes.isNullOrBlank()) "Alias & notes" else "Edit details")
                    }
                }
                OutlinedButton(onClick = onHide) {
                    Icon(Icons.Default.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hide")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(4.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }
            if (!pin.canEdit) {
                Text(
                    "This kind of device rotates its address, so a pin or alias cannot be attached to it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun HiddenDevicesDialog(pins: List<MapPin>, onShow: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hidden devices") },
        text = {
            Column {
                if (pins.isEmpty()) Text("Nothing is hidden.", style = MaterialTheme.typography.bodyMedium)
                pins.forEach { pin ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(categoryIcon(pin.category), contentDescription = null, tint = DetectionColors.textForCategory(pin.category), modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pin.alias ?: pin.latest.displayName, style = MaterialTheme.typography.bodyMedium)
                            Text(pin.macAddress, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { onShow(pin.macAddress) }) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Show")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}

@Composable
private fun DeviceMarker(pin: MapPin, onClick: () -> Unit) {
    val position = pin.position?.toLatLng() ?: return // operator-only drone: just the operator marker
    val markerState = remember(pin.macAddress, position) { MarkerState(position) }
    val hue = when (pin.category) {
        DeviceCategory.FLOCK_ALPR -> BitmapDescriptorFactory.HUE_ORANGE
        DeviceCategory.GUNSHOT_DETECTOR -> BitmapDescriptorFactory.HUE_VIOLET
        DeviceCategory.LAW_ENFORCEMENT -> BitmapDescriptorFactory.HUE_AZURE
        DeviceCategory.WEARABLE_CAMERA -> BitmapDescriptorFactory.HUE_CYAN
        DeviceCategory.DRONE -> BitmapDescriptorFactory.HUE_YELLOW
    }
    val icon = remember(hue) { BitmapDescriptorFactory.defaultMarker(hue) }

    Marker(
        state = markerState,
        title = "${pin.latest.deviceType.label} · ${pin.alias ?: pin.latest.displayName}",
        icon = icon,
        onClick = { onClick(); true }, // true = consume the tap; no SDK info window
    )
}

/**
 * Remote ID: the pilot's position from the System message, drawn as a rose marker with a dashed
 * line back to the aircraft so it is obvious which operator belongs to which drone.
 */
@Composable
private fun OperatorMarker(pin: MapPin, onClick: () -> Unit) {
    val operator = pin.operatorPosition?.toLatLng() ?: return
    val markerState = remember(pin.macAddress, operator) { MarkerState(operator) }
    val icon = remember { BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_ROSE) }
    val label = pin.latest.uasId ?: pin.latest.displayName

    pin.position?.let { aircraft ->
        Polyline(
            points = listOf(aircraft.toLatLng(), operator),
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
