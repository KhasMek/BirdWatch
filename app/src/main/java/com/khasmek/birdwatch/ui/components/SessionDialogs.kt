package com.khasmek.birdwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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

/** Give a session a name of your own ("Downtown loop"); Clear goes back to showing the start time. */
@Composable
fun RenameSessionDialog(current: String?, startedAt: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
        text = {
            Column {
                Text(
                    "Started $startedAt. Without a name the list shows the start time.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Name") },
                    placeholder = { Text("e.g. Downtown loop") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (!current.isNullOrBlank()) TextButton(onClick = { onSave(null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
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
