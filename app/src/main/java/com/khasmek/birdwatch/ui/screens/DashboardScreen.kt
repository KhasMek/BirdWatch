package com.khasmek.birdwatch.ui.screens

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.birdwatch.R
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.DeviceEditDialog
import com.khasmek.birdwatch.ui.components.DeleteDeviceDialog
import com.khasmek.birdwatch.ui.components.DeviceCard
import com.khasmek.birdwatch.ui.components.DeviceCardActions
import com.khasmek.birdwatch.ui.components.ExportFormatDialog
import com.khasmek.birdwatch.ui.components.StatsBar
import com.khasmek.birdwatch.ui.components.categoryIcon
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.usb.UsbStatus
import com.khasmek.birdwatch.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = appViewModel { DashboardViewModel(it) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by rememberSaveable { mutableStateOf(false) }
    var aliasMac by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteMac by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() } }

    aliasMac?.let { mac ->
        val device = state.devices.firstOrNull { it.macAddress == mac }
        DeviceEditDialog(
            currentAlias = state.overrides[mac]?.alias,
            currentNotes = state.overrides[mac]?.notes,
            detectedName = device?.displayName ?: mac,
            onDismiss = { aliasMac = null },
            onSave = { alias, notes -> viewModel.setDetails(mac, alias, notes); aliasMac = null },
        )
    }
    deleteMac?.let { mac ->
        val device = state.devices.firstOrNull { it.macAddress == mac }
        if (device == null) {
            deleteMac = null
        } else {
            DeleteDeviceDialog(
                name = state.overrides[mac]?.alias ?: device.displayName,
                macAddress = mac,
                rowCount = 1,
                sessionCount = 1,
                sessionActive = true,
                onDismiss = { deleteMac = null },
                onConfirm = { viewModel.deleteDetection(mac); deleteMac = null },
            )
        }
    }

    if (showExport) {
        ExportFormatDialog(
            onDismiss = { showExport = false },
            onExport = { format ->
                showExport = false
                scope.launch {
                    val intent = viewModel.exportCurrentSession(format)
                    if (intent == null) Toast.makeText(context, "No active session", Toast.LENGTH_SHORT).show()
                    else context.startActivity(intent)
                }
            },
        )
    }

    // Ticks once a second so "12s ago" and the session duration stay fresh.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.isActive) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.app_name))
                        state.session?.let {
                            Text(
                                text = "${it.label ?: "Session"} · ${TimeFormat.duration(it.durationMillis(now))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                actions = {
                    if (state.isActive) {
                        IconButton(onClick = { showExport = true }) {
                            Icon(Icons.Default.Share, contentDescription = "Export this session")
                        }
                    }
                    // ERROR included: after "Could not open USB device" the user needs a way to retry
                    // without unplugging the board.
                    if (state.usb.status == UsbStatus.DISCONNECTED || state.usb.status == UsbStatus.PERMISSION_NEEDED ||
                        state.usb.status == UsbStatus.ERROR
                    ) {
                        IconButton(onClick = viewModel::connectUsb) {
                            Icon(Icons.Default.Usb, contentDescription = if (state.usb.status == UsbStatus.ERROR) "Retry ESP32 connection" else "Connect ESP32")
                        }
                    }
                    // List order: strongest signal first (highlighted) or most recent first.
                    IconButton(onClick = viewModel::toggleSortOrder) {
                        Icon(
                            imageVector = if (state.strongestFirst) Icons.Default.SignalCellularAlt else Icons.Default.Schedule,
                            contentDescription = if (state.strongestFirst) "Sorted by signal strength; switch to most recent first"
                            else "Sorted by most recent; switch to strongest signal first",
                            tint = if (state.strongestFirst) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = viewModel::toggleAudio) {
                        Icon(
                            imageVector = if (state.audioEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                            contentDescription = if (state.audioEnabled) "Mute alerts" else "Unmute alerts",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = viewModel::toggleSession,
                icon = {
                    Icon(
                        imageVector = if (state.isActive) Icons.Default.Stop else Icons.Default.PlayArrow,
                        contentDescription = null,
                    )
                },
                text = { Text(if (state.isActive) "Stop scan" else "Start scan") },
                containerColor = if (state.isActive) MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.primaryContainer,
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            StatsBar(
                total = state.totalCount,
                flock = state.flockCount,
                raven = state.ravenCount,
                other = state.otherCount,
                showOther = state.showOther,
                isScanning = state.scan.isScanning,
                rawAdvertisements = state.scan.rawAdvertisements,
                gpsLocked = state.gpsLocked,
                gpsTracking = state.location.isTracking,
                gpsAccuracyMeters = state.location.fix?.accuracyMeters,
                usbStatus = state.usb.status,
                wifiApScanning = if (state.wifiApEnabled) state.wifi.isScanning else null,
                wifiApCount = state.wifi.lastResultCount,
            )

            if (state.messages.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    state.messages.forEach { m -> StatusMessageRow(m) { runStatusAction(context, it) } }
                }
            }

            if (state.showFilter) {
                CategoryFilterRow(
                    categories = state.presentCategories,
                    selected = state.filter,
                    onSelect = viewModel::setFilter,
                )
            }

            // Re-ranked at most every 10 s of the ticking clock so the list does not jump every second.
            val ordered = remember(state, now / 10_000) { state.orderedDevices(now) }
            when {
                state.devices.isEmpty() -> EmptyState(isActive = state.isActive, summary = state.listeningSummary)
                ordered.isEmpty() -> EmptyFilterState(state.filter!!) { viewModel.setFilter(null) }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.strongestFirst) {
                        item(key = "sort-hint") {
                            Text(
                                "Strongest signal first (devices heard in the last minute), then older ones by time.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(ordered, key = { it.macAddress }) { device ->
                        DeviceCard(
                            device = device,
                            now = now,
                            override = state.overrides[device.macAddress],
                            actions = DeviceCardActions(
                                onAlias = { aliasMac = device.macAddress },
                                onToggleHidden = { viewModel.toggleHidden(device.macAddress) },
                                onDelete = { deleteMac = device.macAddress },
                            ),
                            priorSessions = state.priorSessions(device.macAddress),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMessageRow(message: StatusMessage, onAction: (StatusAction) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = message.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (message.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f),
        )
        message.action?.let { action ->
            TextButton(onClick = { onAction(action) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(action.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Open the system UI that fixes the reported problem. Falls back to the settings page if a dialog is refused. */
private fun runStatusAction(context: Context, action: StatusAction) {
    val intent = when (action) {
        StatusAction.ENABLE_BLUETOOTH -> Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        StatusAction.LOCATION_SETTINGS -> Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        StatusAction.WIFI_SETTINGS ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Intent(Settings.Panel.ACTION_WIFI)
            else Intent(Settings.ACTION_WIFI_SETTINGS)
        StatusAction.APP_SETTINGS ->
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    }
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        // e.g. BLUETOOTH_CONNECT missing for the enable dialog: send them to Bluetooth settings instead.
        val fallback = when (action) {
            StatusAction.ENABLE_BLUETOOTH -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            StatusAction.WIFI_SETTINGS -> Intent(Settings.ACTION_WIFI_SETTINGS)
            else -> Intent(Settings.ACTION_SETTINGS)
        }
        runCatching { context.startActivity(fallback) }
    }
}

@Composable
private fun CategoryFilterRow(
    categories: List<DeviceCategory>,
    selected: DeviceCategory?,
    onSelect: (DeviceCategory?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("All") })
        categories.forEach { c ->
            FilterChip(
                selected = selected == c,
                onClick = { onSelect(if (selected == c) null else c) },
                label = { Text(c.shortLabel) },
                leadingIcon = {
                    Icon(categoryIcon(c), contentDescription = null, tint = DetectionColors.textForCategory(c), modifier = Modifier.height(16.dp))
                },
            )
        }
    }
}

@Composable
private fun EmptyState(isActive: Boolean, summary: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (isActive) "Listening…" else "Ready",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isActive) summary
            else "Press Start scan to begin a session. Detections are GPS-tagged and saved automatically.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EmptyFilterState(filter: DeviceCategory, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No ${filter.label.lowercase()} detections", style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClear) { Text("Show all") }
    }
}
