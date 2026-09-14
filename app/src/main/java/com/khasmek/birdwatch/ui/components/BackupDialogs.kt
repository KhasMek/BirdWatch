package com.khasmek.birdwatch.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.khasmek.birdwatch.data.ExportFormat
import com.khasmek.birdwatch.data.ParsedBackup
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.ui.theme.DetectionColors

/** Checkbox list of categories with device counts. Categories with no data are not offered. */
@Composable
private fun CategoryPicker(
    counts: Map<DeviceCategory, Int>,
    selected: Set<DeviceCategory>,
    onToggle: (DeviceCategory) -> Unit,
) {
    DeviceCategory.entries.filter { (counts[it] ?: 0) > 0 }.forEach { c ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(c) }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = c in selected, onCheckedChange = { onToggle(c) })
            Icon(categoryIcon(c), contentDescription = null, tint = DetectionColors.forCategory(c), modifier = Modifier.height(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(c.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "${counts[c]}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Choose categories and a format, then the caller opens the system "save as" picker. */
@Composable
fun BackupDialog(
    counts: Map<DeviceCategory, Int>,
    onDismiss: () -> Unit,
    onBackup: (categories: Set<DeviceCategory>, format: ExportFormat) -> Unit,
) {
    val available = remember(counts) { DeviceCategory.entries.filter { (counts[it] ?: 0) > 0 }.toSet() }
    var selected by remember(available) { mutableStateOf(available) }
    var format by remember { mutableStateOf(ExportFormat.JSON) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Back up") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (available.isEmpty()) {
                    Text("There are no detections to back up yet.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("What to include", style = MaterialTheme.typography.labelLarge)
                    CategoryPicker(counts, selected, onToggle = { c -> selected = if (c in selected) selected - c else selected + c })
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    Text("Format", style = MaterialTheme.typography.labelLarge)
                    ExportFormat.entries.forEach { f ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { format = f }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = format == f, onClick = { format = f })
                            Column {
                                Text(f.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    when (f) {
                                        ExportFormat.JSON -> "Complete; the format Restore expects"
                                        ExportFormat.CSV -> "Restorable; session names and exact start/end times are not kept"
                                        ExportFormat.KML -> "Google Earth, one folder per session; cannot be restored"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Backups contain GPS coordinates. You'll pick where to save the file next.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onBackup(selected, format) }, enabled = selected.isNotEmpty()) { Text("Choose location") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shows what a picked backup contains and lets the user leave categories out before merging. */
@Composable
fun RestoreDialog(
    backup: ParsedBackup,
    onDismiss: () -> Unit,
    onRestore: (categories: Set<DeviceCategory>) -> Unit,
) {
    val counts = remember(backup) { backup.categoryCounts }
    var selected by remember(backup) { mutableStateOf(counts.keys) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "${backup.format.label} backup with ${backup.sessions.size} session${if (backup.sessions.size == 1) "" else "s"} " +
                        "and ${backup.deviceCount} device${if (backup.deviceCount == 1) "" else "s"}.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (counts.isEmpty()) {
                    Text("The file has no detections.", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Text("What to restore", style = MaterialTheme.typography.labelLarge)
                    CategoryPicker(counts, selected, onToggle = { c -> selected = if (c in selected) selected - c else selected + c })
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Restoring merges: sessions you already have keep everything and gain any missing " +
                        "detections; sessions you don't have are added.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(selected) }, enabled = selected.isNotEmpty()) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun DeleteAllDialog(sessions: Int, devices: Int, sessionActive: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete all data?") },
        text = {
            Text(
                "This removes all $sessions session${if (sessions == 1) "" else "s"} and $devices " +
                    "detection${if (devices == 1) "" else "s"} from this phone." +
                    (if (sessionActive) " The running scan will be stopped first." else "") +
                    " Settings and your Maps key are kept. This cannot be undone; back up first if you want to keep anything."
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
