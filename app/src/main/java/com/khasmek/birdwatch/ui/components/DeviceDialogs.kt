package com.khasmek.birdwatch.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Set or clear a device's alias and notes. Shared by the map sheet and the device cards.
 * [onSave] receives the raw field texts; the editor trims and treats blank as cleared.
 */
@Composable
fun DeviceEditDialog(
    currentAlias: String?,
    currentNotes: String?,
    detectedName: String,
    onDismiss: () -> Unit,
    onSave: (alias: String?, notes: String?) -> Unit,
) {
    var alias by rememberSaveable { mutableStateOf(currentAlias.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(currentNotes.orEmpty()) }
    val hasSomething = !currentAlias.isNullOrBlank() || !currentNotes.isNullOrBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alias & notes") },
        text = {
            Column {
                Text(
                    "Your own name and notes for this device. The detected name ($detectedName) stays visible underneath. " +
                        "Both travel with exports and backups.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    singleLine = true,
                    label = { Text("Alias") },
                    placeholder = { Text("e.g. Camera at Main & 3rd") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    minLines = 3,
                    maxLines = 6,
                    label = { Text("Notes") },
                    placeholder = { Text("e.g. pole on the NE corner, facing south; confirmed by eye") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(alias, notes) }) { Text("Save") } },
        dismissButton = {
            Row {
                if (hasSomething) TextButton(onClick = { onSave(null, null) }) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/**
 * Confirm removing a device's detections. [rowCount] rows across [sessionCount] sessions go;
 * [sessionActive] warns that a device still in range will come straight back.
 */
@Composable
fun DeleteDeviceDialog(
    name: String,
    macAddress: String,
    rowCount: Int,
    sessionCount: Int,
    sessionActive: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete detection?") },
        text = {
            Text(
                buildString {
                    append("This removes $name ($macAddress) from ")
                    append(if (rowCount == 1) "this session." else "all $sessionCount sessions shown ($rowCount detections).")
                    if (sessionActive) append(" If it is still in range it will be picked up again as a new detection.")
                    append(" To keep the data but drop the map pin, use Hide instead. This cannot be undone.")
                }
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
