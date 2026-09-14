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
import com.khasmek.birdwatch.usb.UsbCompanion

/** Pick which of the ESP32's stored tables to import (memory since boot, or flash from the previous run). */
@Composable
fun ImportFromEsp32Dialog(
    connected: Boolean,
    onDismiss: () -> Unit,
    onImport: (UsbCompanion.DumpSource) -> Unit,
) {
    var selected by remember { mutableStateOf(UsbCompanion.DumpSource.PREV) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Import from ESP32") },
        text = {
            Column {
                Text(
                    "The ESP32 keeps its own detection table when it runs without the phone. Pull it in as a " +
                        "new session. Imported detections have no GPS and are timestamped at import, since the " +
                        "device has no clock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                UsbCompanion.DumpSource.entries.forEach { s ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = selected == s, role = Role.RadioButton, onClick = { selected = s })
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = selected == s, onClick = null)
                        Column {
                            Text(
                                when (s) {
                                    UsbCompanion.DumpSource.PREV -> "Previous run (flash)"
                                    UsbCompanion.DumpSource.LIVE -> "Current run (memory)"
                                },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                when (s) {
                                    UsbCompanion.DumpSource.PREV -> "The run before the device was last powered off"
                                    UsbCompanion.DumpSource.LIVE -> "Everything the device has seen since it booted"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (!connected) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Plug in the ESP32 first. It doesn't need a session to be running.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onImport(selected) }, enabled = connected) { Text("Import") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
