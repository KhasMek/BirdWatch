package com.khasmek.birdwatch.util

import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.BuildConfig
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Builds the text behind Settings > "Copy diagnostics". Everything here is a version, a state,
 * or a count; the few free-text fields (radio error messages, the firmware banner) are our own
 * strings or pass through [Diagnostics.scrub]. Session contents are never read.
 */
class DiagnosticsReport(private val container: AppContainer) {

    suspend fun build(): String {
        val ctx = container.appContext
        val db = container.database
        val scan = container.bleScanner.status.value
        val usb = container.usbCompanion.state.value
        val loc = container.locationProvider.state.value
        val wifi = container.wifiApScanner.status.value
        val settings = container.settings
        val session = container.sessionManager.currentSession.value

        val bt = ctx.getSystemService(BluetoothManager::class.java)?.adapter
        val lm = ctx.getSystemService(LocationManager::class.java)
        val wm = ctx.getSystemService(WifiManager::class.java)

        return buildString {
            appendLine("BirdWatch diagnostics")
            appendLine("Contains versions, states and counts only: no detections, addresses, coordinates or names.")
            appendLine()
            appendLine(Diagnostics.line("generated", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()))
            appendLine(Diagnostics.line("app", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})"))
            appendLine(Diagnostics.line("android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"))
            appendLine(Diagnostics.line("device", "${Build.MANUFACTURER} ${Build.MODEL}"))
            appendLine(Diagnostics.line("built_in_maps_key", MapsKeyInjector.builtInKey != null))
            appendLine(Diagnostics.line("user_maps_key", container.secureSettings.mapsApiKey.value != null))
            appendLine()
            appendLine("Permissions")
            Permissions.all.forEach { p ->
                val granted = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
                appendLine(Diagnostics.line("  " + p.substringAfterLast('.'), if (granted) "granted" else "denied"))
            }
            appendLine()
            appendLine("System")
            appendLine(Diagnostics.line("  bluetooth", when { bt == null -> "no adapter"; bt.isEnabled -> "on"; else -> "off" }))
            appendLine(Diagnostics.line("  location_services", lm?.let { if (LocationManagerCompat.isLocationEnabled(it)) "on" else "off" }))
            appendLine(Diagnostics.line("  wifi", wm?.let { if (it.isWifiEnabled) "on" else "off" }))
            appendLine(Diagnostics.line("  usb_host", ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)))
            appendLine()
            appendLine("Radios")
            appendLine(Diagnostics.line("  session_active", session != null))
            appendLine(Diagnostics.line("  ble_scanning", scan.isScanning))
            appendLine(Diagnostics.line("  ble_mode", when (scan.scanMode) {
                ScanSettings.SCAN_MODE_LOW_LATENCY -> "LOW_LATENCY"
                ScanSettings.SCAN_MODE_BALANCED -> "BALANCED"
                ScanSettings.SCAN_MODE_LOW_POWER -> "LOW_POWER"
                else -> scan.scanMode.toString()
            }))
            appendLine(Diagnostics.line("  ble_raw_adverts", scan.rawAdvertisements))
            appendLine(Diagnostics.line("  ble_error", scan.error?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  ble_warning", scan.warning?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  usb_status", usb.status.name))
            appendLine(Diagnostics.line("  usb_device", usb.deviceDescription?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  usb_error", usb.error?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  usb_lines", usb.linesReceived))
            appendLine(Diagnostics.line("  usb_detections", usb.detectionsReceived))
            appendLine(Diagnostics.line("  firmware_config", usb.config?.let { "beepMask=${it.beepMask} ouiCount=${it.ouiCount}" }))
            appendLine(Diagnostics.line("  firmware_banner", usb.lastText?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  gps_tracking", loc.isTracking))
            appendLine(Diagnostics.line("  gps_has_fix", loc.hasFix))
            appendLine(Diagnostics.line("  gps_accuracy_m", loc.fix?.accuracyMeters?.toInt()))
            appendLine(Diagnostics.line("  gps_error", loc.error?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  wifi_scan_enabled", settings.wifiApScan.value))
            appendLine(Diagnostics.line("  wifi_scanning", wifi.isScanning))
            appendLine(Diagnostics.line("  wifi_error", wifi.error?.let(Diagnostics::scrub)))
            appendLine(Diagnostics.line("  wifi_warning", wifi.warning?.let(Diagnostics::scrub)))
            appendLine()
            appendLine("Settings")
            appendLine(Diagnostics.line("  audio_alerts", settings.audioAlerts.value))
            appendLine(Diagnostics.line("  quiet_known_alerts", settings.quietKnownAlerts.value))
            appendLine(Diagnostics.line("  low_power_scan", settings.lowPowerScan.value))
            appendLine(Diagnostics.line("  packs", settings.enabledPacks.value.joinToString(",") { it.name }.ifEmpty { "none" }))
            appendLine(Diagnostics.line("  track_all_sightings", settings.trackAllSightings.value))
            appendLine()
            appendLine("Data (counts only)")
            appendLine(Diagnostics.line("  sessions", db.sessionDao().count()))
            appendLine(Diagnostics.line("  detections", db.detectionDao().count()))
            appendLine(Diagnostics.line("  device_edits", db.deviceOverrideDao().count()))
            appendLine(Diagnostics.line("  trail_points", db.sightingSampleDao().countAll()))
            appendLine()
            appendLine("Last crash")
            appendLine(CrashRecorder.lastCrash(ctx) ?: "none recorded")
        }
    }
}
