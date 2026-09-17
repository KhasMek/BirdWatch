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

/** Set or clear a device's alias. Shared by the map sheet and the device cards. */
@Composable
fun AliasDialog(current: String?, detectedName: String, onDismiss: () -> Unit, onSave: (String?) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Alias") },
        text = {
            Column {
                Text(
                    "A name of your own for this device. The detected name ($detectedName) stays visible underneath.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Alias") },
                    placeholder = { Text("e.g. Camera at Main & 3rd") },
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
