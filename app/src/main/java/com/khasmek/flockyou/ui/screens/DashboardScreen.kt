package com.khasmek.flockyou.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.flockyou.R
import com.khasmek.flockyou.ui.appViewModel
import com.khasmek.flockyou.ui.components.DeviceCard
import com.khasmek.flockyou.ui.components.ExportFormatDialog
import com.khasmek.flockyou.ui.components.StatsBar
import com.khasmek.flockyou.usb.UsbStatus
import com.khasmek.flockyou.util.TimeFormat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(viewModel: DashboardViewModel = appViewModel { DashboardViewModel(it) }) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by remember { mutableStateOf(false) }

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
                                text = "Session ${TimeFormat.duration(it.durationMillis(now))}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    if (state.usb.status == UsbStatus.DISCONNECTED || state.usb.status == UsbStatus.PERMISSION_NEEDED) {
                        IconButton(onClick = viewModel::connectUsb) {
                            Icon(Icons.Default.Usb, contentDescription = "Connect ESP32")
                        }
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
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    state.messages.forEach { m ->
                        Text(
                            text = m.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (m.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            if (state.devices.isEmpty()) {
                EmptyState(isActive = state.isActive, usbConnected = state.usb.isConnected)
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.devices, key = { it.macAddress }) { device ->
                        DeviceCard(device = device, now = now)
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(isActive: Boolean, usbConnected: Boolean) {
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
            text = when {
                !isActive -> "Press Start scan to begin a session. Detections are GPS-tagged and saved automatically."
                usbConnected -> "Phone BLE and the ESP32 are both scanning. Flock cameras and Ravens will appear here."
                else -> "Phone BLE is scanning for Ravens. Plug in the ESP32 over USB to detect Flock cameras."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
