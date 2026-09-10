package com.khasmek.flockyou.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.flockyou.FlockYouApp
import com.khasmek.flockyou.detection.Confidence

/**
 * TEMPORARY Phase 2 debug panel so the detection engine can be exercised on a device.
 * Replaced by the real dashboard (ViewModel, stats bar, device cards, audio) in Phase 4.
 */
@Composable
fun DashboardScreen() {
    val scanner = FlockYouApp.instance.container.bleScanner
    val status by scanner.status.collectAsStateWithLifecycle()
    val devices by scanner.devices.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Dashboard (Phase 2 debug)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (status.isScanning) "Scanning · ${status.rawAdvertisements} adverts seen" else "Idle",
            style = MaterialTheme.typography.bodyMedium
        )
        status.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        status.warning?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (status.isScanning) scanner.stop() else scanner.start() }) {
                Text(if (status.isScanning) "Stop scan" else "Start scan")
            }
            OutlinedButton(onClick = { scanner.clear() }) { Text("Clear") }
        }
        Spacer(Modifier.height(12.dp))
        Text("Detections: ${devices.size}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices, key = { it.macAddress }) { d ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${d.deviceType.label} · ${d.displayName}", style = MaterialTheme.typography.titleSmall)
                        Text(d.macAddress, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${d.detectionMethod.label} (${d.matchedOn})" +
                                (if (d.confidence == Confidence.LOW) " · low confidence" else "") +
                                (d.ravenFirmware?.let { " · fw $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text("RSSI ${d.rssi} dBm · seen ${d.sightings}x", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
