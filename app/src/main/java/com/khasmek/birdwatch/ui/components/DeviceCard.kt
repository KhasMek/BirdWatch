package com.khasmek.birdwatch.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.RemoveRedEye
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SignalCellularAlt1Bar
import androidx.compose.material.icons.filled.SignalCellularAlt2Bar
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.detection.DeviceType
import com.khasmek.birdwatch.ui.theme.DetectionColors
import com.khasmek.birdwatch.util.TimeFormat
import java.util.Locale

/**
 * One detected device. Collapsed: type badge, name/MAC, method tag, RSSI bars, last seen.
 * Tap to expand: GPS, first/last seen clock times, sightings, matched value, tier/channel/firmware.
 */
@Composable
fun DeviceCard(
    device: DetectedDevice,
    now: Long,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false,
) {
    var expanded by rememberSaveable(device.macAddress) { mutableStateOf(initiallyExpanded) }
    val typeColor = DetectionColors.forType(device.deviceType)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TypeBadge(device.deviceType, typeColor)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = device.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = device.macAddress,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                RssiIndicator(device.rssi)
            }

            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Tag(text = device.detectionMethod.label, color = MaterialTheme.colorScheme.secondaryContainer,
                    onColor = MaterialTheme.colorScheme.onSecondaryContainer)
                SourceTag(device.source)
                device.tier?.let {
                    Tag(text = "T$it", color = MaterialTheme.colorScheme.tertiaryContainer,
                        onColor = MaterialTheme.colorScheme.onTertiaryContainer)
                }
                if (device.confidence == Confidence.LOW) {
                    Tag(text = "low conf.", color = MaterialTheme.colorScheme.errorContainer,
                        onColor = MaterialTheme.colorScheme.onErrorContainer)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = TimeFormat.relative(device.lastSeen, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    DetailRow("Location", if (device.hasLocation) {
                        coords(device.latitude!!, device.longitude!!) +
                            (device.accuracyMeters?.let { " (±${it.toInt()} m)" } ?: "")
                    } else "No GPS fix at detection")
                    DetailRow("First seen", TimeFormat.clock(device.firstSeen))
                    DetailRow("Last seen", TimeFormat.clock(device.lastSeen))
                    DetailRow("Sightings", device.sightings.toString())
                    DetailRow("RSSI", "${device.rssi} dBm")
                    DetailRow("Matched on", device.matchedOn)
                    device.channel?.let { DetailRow("WiFi channel", it.toString()) }
                    device.ravenFirmware?.let { DetailRow("Raven firmware", it) }
                    if (device.isRemoteId) {
                        device.uasId?.let { DetailRow("UAS ID", it) }
                        device.operatorId?.let { DetailRow("Operator ID", it) }
                        if (device.hasTargetLocation) {
                            DetailRow(
                                "Drone position",
                                coords(device.targetLatitude!!, device.targetLongitude!!) +
                                    (device.targetAltitudeM?.let { "  alt ${it.toInt()} m" } ?: ""),
                            )
                        }
                        if (device.hasOperatorLocation) {
                            DetailRow("Operator", coords(device.operatorLatitude!!, device.operatorLongitude!!))
                        }
                    }
                }
            }
        }
    }
}

/** "37.12345, -122.98765": always a dot for the decimal point, whatever the phone's locale. */
fun coords(lat: Double, lon: Double): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)

/** Icon per category, shared with the stats bar and session rows. */
fun categoryIcon(category: DeviceCategory): ImageVector = when (category) {
    DeviceCategory.FLOCK_ALPR -> Icons.Default.Videocam
    DeviceCategory.GUNSHOT_DETECTOR -> Icons.Default.Hearing
    DeviceCategory.LAW_ENFORCEMENT -> Icons.Default.LocalPolice
    DeviceCategory.WEARABLE_CAMERA -> Icons.Default.RemoveRedEye
    DeviceCategory.DRONE -> Icons.Default.Flight
}

@Composable
private fun TypeBadge(type: DeviceType, color: Color) {
    val icon = categoryIcon(type.category)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .background(color, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = DetectionColors.OnAccent, modifier = Modifier.size(20.dp))
        Text(
            text = type.label,
            style = MaterialTheme.typography.labelSmall,
            color = DetectionColors.OnAccent,
        )
    }
}

@Composable
private fun SourceTag(source: DetectionSource) {
    val color = DetectionColors.textForSource(source)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Icon(
            imageVector = when (source) {
                DetectionSource.BLE -> Icons.Default.Bluetooth
                DetectionSource.ESP32_WIFI -> Icons.Default.Usb
                DetectionSource.PHONE_WIFI -> Icons.Default.Wifi
            },
            contentDescription = null, // the text beside it names the source
            tint = color,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(3.dp))
        Text(
            text = when (source) {
                DetectionSource.BLE -> "BLE"
                DetectionSource.ESP32_WIFI -> "ESP32"
                DetectionSource.PHONE_WIFI -> "WiFi"
            },
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun Tag(text: String, color: Color, onColor: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = onColor,
        modifier = Modifier
            .background(color, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** Signal strength: 3 bars > -60 dBm, 2 bars > -75, 1 bar otherwise. */
@Composable
fun RssiIndicator(rssi: Int) {
    val (icon, tint) = when {
        rssi > -60 -> Icons.Default.SignalCellularAlt to Color(0xFF2E7D32)
        rssi > -75 -> Icons.Default.SignalCellularAlt2Bar to Color(0xFFF9A825)
        else -> Icons.Default.SignalCellularAlt1Bar to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = "Signal $rssi dBm", tint = tint, modifier = Modifier.size(22.dp))
        Text("$rssi", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(110.dp),
        )
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}
