package com.khasmek.birdwatch.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.khasmek.birdwatch.data.SessionSummary
import com.khasmek.birdwatch.ui.appViewModel
import com.khasmek.birdwatch.ui.components.ConfirmDeleteDialog
import com.khasmek.birdwatch.ui.components.ExportFormatDialog
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.util.TimeFormat
import kotlinx.coroutines.launch

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

    Scaffold(topBar = { TopAppBar(title = { Text("Sessions") }) }) { innerPadding ->
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
                    "Every scan you start from the Dashboard is saved here with its detections.",
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
                    text = TimeFormat.dateTime(s.startedAt) + if (isActive) "  ·  ACTIVE" else "",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Duration ${TimeFormat.duration(s.durationMillis())}" +
                        (if (isActive) " (running)" else ""),
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
