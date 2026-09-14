package com.khasmek.birdwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.khasmek.birdwatch.data.ExportFormat

/** Pick JSON / CSV / KML, then share. */
@Composable
fun ExportFormatDialog(onDismiss: () -> Unit, onExport: (ExportFormat) -> Unit) {
    var selected by remember { mutableStateOf(ExportFormat.JSON) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Export session") },
        text = {
            Column {
                ExportFormat.entries.forEach { f ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == f, role = Role.RadioButton, onClick = { selected = f })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == f, onClick = null)
                        Column {
                            Text(f.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                when (f) {
                                    ExportFormat.JSON -> "Full detail, machine-readable"
                                    ExportFormat.CSV -> "Spreadsheets; one row per device"
                                    ExportFormat.KML -> "Google Earth; only devices with GPS"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onExport(selected) }) { Text("Share") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDeleteDialog(deviceCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete session?") },
        text = {
            Text(
                if (deviceCount == 0) "This session has no detections."
                else "This removes the session and its $deviceCount detection${if (deviceCount == 1) "" else "s"}. This cannot be undone."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
