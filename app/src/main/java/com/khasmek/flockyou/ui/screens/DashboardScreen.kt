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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.flockyou.FlockYouApp
import com.khasmek.flockyou.detection.Confidence

/**
 * TEMPORARY Phase 2/3 debug panel so the engine + persistence can be exercised on a device.
 * The device list here comes from Room (current session), not from the scanner's memory, so a
 * row appearing proves the whole pipeline. Replaced by the real dashboard in Phase 5.
 */
@Composable
fun DashboardScreen() {
    val container = FlockYouApp.instance.container
    val scanner = container.bleScanner
    val sessionManager = container.sessionManager

    val status by scanner.status.collectAsStateWithLifecycle()
    val location by container.locationProvider.state.collectAsStateWithLifecycle()
    val session by sessionManager.currentSession.collectAsStateWithLifecycle()
    val devices by sessionManager.observeCurrentDevices().collectAsStateWithLifecycle(emptyList())

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("Dashboard (Phase 3 debug)", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            text = session?.let { "Session ${it.id.take(8)}… · " } .orEmpty() +
                if (status.isScanning) "Scanning · ${status.rawAdvertisements} adverts seen" else "Idle",
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = location.fix?.let { f ->
                "GPS %.5f, %.5f (±%.0f m)".format(f.latitude, f.longitude, f.accuracyMeters ?: -1f)
            } ?: if (location.isTracking) "GPS: waiting for fix…" else "GPS: off",
            style = MaterialTheme.typography.bodySmall
        )
        status.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        status.warning?.let {
            Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (session != null) sessionManager.stop() else sessionManager.start() }) {
                Text(if (session != null) "Stop session" else "Start session")
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Persisted this session: ${devices.size}", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices, key = { it.macAddress }) { d ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text("${d.deviceType.label} · ${d.displayName}", style = MaterialTheme.typography.titleSmall)
                        Text("${d.macAddress} · ${d.source.label}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${d.detectionMethod.label} (${d.matchedOn})" +
                                (if (d.confidence == Confidence.LOW) " · low confidence" else "") +
                                (d.ravenFirmware?.let { " · fw $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "RSSI ${d.rssi} dBm · seen ${d.sightings}x" +
                                (if (d.hasLocation) " · %.5f, %.5f".format(d.latitude, d.longitude) else " · no GPS"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
