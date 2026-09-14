package com.khasmek.birdwatch.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.SessionManager
import com.khasmek.birdwatch.data.SessionSummary
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.ConfirmDeleteDialog
import com.khasmek.birdwatch.ui.components.ExportFormatDialog
import com.khasmek.birdwatch.ui.components.ImportFromEsp32Dialog
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.util.TimeFormat
import kotlinx.coroutines.launch

/** MIME types offered to the document picker for BirdWatch export files. */
private val IMPORT_MIME_TYPES = arrayOf("application/json", "text/csv", "text/comma-separated-values", "text/plain", "application/octet-stream")

/** List of every scan session, newest first. Tap for details; overflow menu for export / delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviousSessionScreen(
    onOpenSession: (String) -> Unit,
    viewModel: SessionsViewModel = appViewModel { SessionsViewModel(it) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var exportTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<SessionSummary?>(null) }
    var showImport by remember { mutableStateOf(false) }

    // Restore one of the app's own JSON/CSV exports. OpenDocument gives a persistable, read-only
    // grant to exactly the file the user picked.
    val pickExport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        viewModel.importFile(uri) { result ->
            val msg = result.fold(
                onSuccess = { "Imported ${it.devices.size} device${if (it.devices.size == 1) "" else "s"} from ${it.format.label}" },
                onFailure = { e ->
                    when (e) {
                        is SessionManager.AlreadyImportedException -> "That session is already in the app"
                        else -> "Import failed: ${e.message}"
                    }
                },
            )
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    if (showImport) {
        // Opening the dialog also nudges a connection so a freshly plugged-in board is usable.
        LaunchedEffect(Unit) { viewModel.connectUsb() }
        ImportFromEsp32Dialog(
            connected = state.usb.isConnected,
            onDismiss = { showImport = false },
            onImport = { source ->
                showImport = false
                viewModel.importFromEsp32(source) { result ->
                    val msg = result.fold(
                        onSuccess = { r ->
                            if (r.imported == 0) "ESP32 ${source.label} is empty; nothing to import"
                            else "Imported ${r.imported} device${if (r.imported == 1) "" else "s"} from ESP32 ${source.label}"
                        },
                        onFailure = { "Import failed: ${it.message}" },
                    )
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    exportTarget?.let { target ->
        ExportFormatDialog(
            onDismiss = { exportTarget = null },
            onExport = { format ->
                exportTarget = null
                scope.launch {
                    val intent = viewModel.export(target.session.id, format)
                    if (intent == null) Toast.makeText(context, "Session not found", Toast.LENGTH_SHORT).show()
                    else context.startActivity(intent)
                }
            },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            deviceCount = target.deviceCount,
            onDismiss = { deleteTarget = null },
            onConfirm = { viewModel.delete(target.session.id); deleteTarget = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                actions = {
                    if (state.importing) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    } else {
                        var menu by remember { mutableStateOf(false) }
                        IconButton(onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Import from ESP32…") },
                                leadingIcon = { Icon(Icons.Default.Usb, null) },
                                onClick = { menu = false; showImport = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Import session…") },
                                leadingIcon = { Icon(Icons.Default.FileOpen, null) },
                                onClick = { menu = false; pickExport.launch(IMPORT_MIME_TYPES) },
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.sessions.isEmpty()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No sessions yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Every scan you start from the Dashboard is saved here with its detections. " +
                        "The menu above can import a session from an exported JSON/CSV file, or pull in what " +
                        "the ESP32 recorded on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.sessions, key = { it.session.id }) { summary ->
                    SessionRow(
                        summary = summary,
                        isActive = summary.session.id == state.activeSessionId,
                        onClick = { onOpenSession(summary.session.id) },
                        onExport = { exportTarget = summary },
                        onDelete = { deleteTarget = summary },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    summary: SessionSummary,
    isActive: Boolean,
    onClick: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    val s = summary.session
    var menu by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow
        ),
    ) {
        Row(Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = (s.label ?: TimeFormat.dateTime(s.startedAt)) + if (isActive) "  ·  ACTIVE" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        // ESP32 dumps carry no clock and no GPS; restored exports keep both.
                        s.label?.startsWith("ESP32") == true -> "Imported ${TimeFormat.dateTime(s.startedAt)} · no GPS"
                        s.isImported -> "${TimeFormat.dateTime(s.startedAt)} · ${TimeFormat.duration(s.durationMillis())}"
                        else -> "Duration ${TimeFormat.duration(s.durationMillis())}" + (if (isActive) " (running)" else "")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${summary.deviceCount} device${if (summary.deviceCount == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(12.dp))
                    Icon(Icons.Default.Videocam, null, tint = DetectionColors.Flock, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${summary.flockCount}", style = MaterialTheme.typography.bodyMedium, color = DetectionColors.Flock)
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.Default.Hearing, null, tint = DetectionColors.Raven, modifier = Modifier.height(16.dp))
                    Spacer(Modifier.width(3.dp))
                    Text("${summary.ravenCount}", style = MaterialTheme.typography.bodyMedium, color = DetectionColors.Raven)
                    if (summary.otherCount > 0) {
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Default.Category, null, tint = DetectionColors.LawEnforcement, modifier = Modifier.height(16.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("${summary.otherCount}", style = MaterialTheme.typography.bodyMedium, color = DetectionColors.LawEnforcement)
                    }
                }
            }
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = "Session options")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Export…") },
                    leadingIcon = { Icon(Icons.Default.Share, null) },
                    onClick = { menu = false; onExport() },
                )
                DropdownMenuItem(
                    text = { Text("Delete") },
                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error) },
                    enabled = !isActive,
                    onClick = { menu = false; onDelete() },
                )
            }
        }
    }
}
