package com.khasmek.birdwatch.data

import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.edit
import com.khasmek.birdwatch.detection.PackId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain user preferences (toggles), backed by SharedPreferences and exposed as StateFlows.
 * The Google Maps API key is NOT here; it goes in EncryptedSharedPreferences ([SecureSettings]).
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _audioAlerts = MutableStateFlow(prefs.getBoolean(KEY_AUDIO, true))
    /** Play a chirp when a new device is detected. */
    val audioAlerts: StateFlow<Boolean> = _audioAlerts.asStateFlow()

    private val _lowPowerScan = MutableStateFlow(prefs.getBoolean(KEY_LOW_POWER, false))
    /** Prefer SCAN_MODE_LOW_POWER over SCAN_MODE_LOW_LATENCY for the phone BLE scanner. */
    val lowPowerScan: StateFlow<Boolean> = _lowPowerScan.asStateFlow()

    private val _enabledPacks = MutableStateFlow(loadPacks())
    /** Opt-in signature packs currently switched on. Core (Flock + Raven) is always on. */
    val enabledPacks: StateFlow<Set<PackId>> = _enabledPacks.asStateFlow()

    private val _wifiApScan = MutableStateFlow(prefs.getBoolean(KEY_WIFI_AP, false))
    /** Use the phone's WiFi radio to match access-point BSSIDs (third detection source). */
    val wifiApScan: StateFlow<Boolean> = _wifiApScan.asStateFlow()

    val scanMode: Int
        get() = if (_lowPowerScan.value) ScanSettings.SCAN_MODE_LOW_POWER else ScanSettings.SCAN_MODE_LOW_LATENCY

    fun setAudioAlerts(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUDIO, enabled) }
        _audioAlerts.value = enabled
    }

    fun setLowPowerScan(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_LOW_POWER, enabled) }
        _lowPowerScan.value = enabled
    }

    fun setWifiApScan(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_WIFI_AP, enabled) }
        _wifiApScan.value = enabled
    }

    fun setPackEnabled(pack: PackId, enabled: Boolean) {
        val next = if (enabled) _enabledPacks.value + pack else _enabledPacks.value - pack
        prefs.edit { putStringSet(KEY_PACKS, next.map { it.name }.toSet()) }
        _enabledPacks.value = next
    }

    private fun loadPacks(): Set<PackId> =
        prefs.getStringSet(KEY_PACKS, emptySet())
            .orEmpty()
            .mapNotNull { name -> PackId.entries.firstOrNull { it.name == name } }
            .toSet()

    private companion object {
        const val FILE = "birdwatch_settings"
        const val KEY_AUDIO = "audio_alerts"
        const val KEY_LOW_POWER = "low_power_scan"
        const val KEY_PACKS = "enabled_packs"
        const val KEY_WIFI_AP = "wifi_ap_scan"
    }
}
