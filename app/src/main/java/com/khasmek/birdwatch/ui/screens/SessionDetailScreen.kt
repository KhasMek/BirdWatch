package com.khasmek.birdwatch.ui.screens

import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.ConfirmDeleteDialog
import com.khasmek.birdwatch.ui.components.DeviceCard
import com.khasmek.birdwatch.ui.components.ExportFormatDialog
import com.khasmek.birdwatch.util.TimeFormat
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    sessionId: String,
    onBack: () -> Unit,
    viewModel: SessionDetailViewModel = appViewModel { SessionDetailViewModel(it, sessionId) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showExport by rememberSaveable { mutableStateOf(false) }
    var showDelete by rememberSaveable { mutableStateOf(false) }

    // Session deleted (here or elsewhere) -> leave the screen.
    LaunchedEffect(state.loaded, state.summary) { if (state.loaded && state.summary == null) onBack() }

    if (showExport) {
        ExportFormatDialog(
            onDismiss = { showExport = false },
            onExport = { format ->
                showExport = false
                scope.launch {
                    val intent = viewModel.export(format)
                    if (intent == null) Toast.makeText(context, "Session not found", Toast.LENGTH_SHORT).show()
                    else context.startActivity(intent)
                }
            },
        )
    }
    if (showDelete) {
        ConfirmDeleteDialog(
            deviceCount = state.devices.size,
            onDismiss = { showDelete = false },
            onConfirm = { showDelete = false; viewModel.delete() },
        )
    }

    val s = state.summary?.session
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s?.label ?: if (state.isActive) "Current session" else "Session")
                        s?.let {
                            Text(
                                (if (it.isImported) "Imported " else "") + TimeFormat.dateTime(it.startedAt),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = { showExport = true }, enabled = s != null) {
                        Icon(Icons.Default.Share, contentDescription = "Export")
                    }
                    IconButton(onClick = { showDelete = true }, enabled = s != null && !state.isActive) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete session")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            state.summary?.let { summary ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Stat("Duration", TimeFormat.duration(summary.session.durationMillis()))
                        Stat("Devices", summary.deviceCount.toString())
                        Stat("Flock", summary.flockCount.toString())
                        Stat("Raven", summary.ravenCount.toString())
                        if (summary.otherCount > 0) Stat("Other", summary.otherCount.toString())
                        Stat("With GPS", state.devices.count { it.hasLocation }.toString())
                    }
                }
            }
            if (state.loaded && state.devices.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("No detections in this session", style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val now = System.currentTimeMillis()
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.devices, key = { it.macAddress }) { device ->
                        DeviceCard(device = device, now = now, alias = state.aliases[device.macAddress])
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
