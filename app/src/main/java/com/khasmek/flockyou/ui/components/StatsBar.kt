package com.khasmek.flockyou.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.UsbOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.khasmek.flockyou.detection.DeviceCategory
import com.khasmek.flockyou.ui.theme.DetectionColors
import com.khasmek.flockyou.usb.UsbStatus

/**
 * Two rows: counts (total / Flock / Raven, plus "Other" when opt-in packs are in play) and
 * radio status (scan / GPS / USB).
 */
@Composable
fun StatsBar(
    total: Int,
    flock: Int,
    raven: Int,
    other: Int,
    showOther: Boolean,
    isScanning: Boolean,
    rawAdvertisements: Long,
    gpsLocked: Boolean,
    gpsTracking: Boolean,
    gpsAccuracyMeters: Float?,
    usbStatus: UsbStatus,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CountStat("Devices", total, MaterialTheme.colorScheme.onSurface)
                CountStat("Flock", flock, DetectionColors.Flock, categoryIcon(DeviceCategory.FLOCK_ALPR))
                CountStat("Raven", raven, DetectionColors.Raven, categoryIcon(DeviceCategory.GUNSHOT_DETECTOR))
                if (showOther) {
                    CountStat("Other", other, DetectionColors.LawEnforcement, Icons.Default.Category)
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusStat(
                    icon = Icons.Default.Radar,
                    label = if (isScanning) "Scanning" else "Paused",
                    detail = if (isScanning) "$rawAdvertisements adv" else null,
                    active = isScanning,
                )
                StatusStat(
                    icon = when {
                        gpsLocked -> Icons.Default.GpsFixed
                        gpsTracking -> Icons.Default.GpsNotFixed
                        else -> Icons.Default.GpsOff
                    },
                    label = when {
                        gpsLocked -> "GPS lock"
                        gpsTracking -> "GPS searching"
                        else -> "GPS off"
                    },
                    detail = if (gpsLocked && gpsAccuracyMeters != null) "±${gpsAccuracyMeters.toInt()} m" else null,
                    active = gpsLocked,
                )
                StatusStat(
                    icon = if (usbStatus == UsbStatus.CONNECTED) Icons.Default.Usb else Icons.Default.UsbOff,
                    label = when (usbStatus) {
                        UsbStatus.CONNECTED -> "ESP32 on"
                        UsbStatus.NO_DEVICE -> "No ESP32"
                        UsbStatus.PERMISSION_NEEDED -> "USB perm"
                        UsbStatus.CONNECTING -> "USB…"
                        UsbStatus.DISCONNECTED -> "ESP32 idle"
                        UsbStatus.ERROR -> "USB error"
                    },
                    detail = null,
                    active = usbStatus == UsbStatus.CONNECTED,
                )
            }
        }
    }
}

@Composable
private fun CountStat(label: String, value: Int, color: Color, icon: ImageVector? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusStat(icon: ImageVector, label: String, detail: String?, active: Boolean) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelMedium, color = tint)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
