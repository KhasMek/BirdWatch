package com.khasmek.flockyou.data

import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain user preferences (toggles), backed by SharedPreferences and exposed as StateFlows.
 * The Google Maps API key is NOT here; it goes in EncryptedSharedPreferences in Phase 7.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _audioAlerts = MutableStateFlow(prefs.getBoolean(KEY_AUDIO, true))
    /** Play a chirp when a new device is detected. */
    val audioAlerts: StateFlow<Boolean> = _audioAlerts.asStateFlow()

    private val _lowPowerScan = MutableStateFlow(prefs.getBoolean(KEY_LOW_POWER, false))
    /** Prefer SCAN_MODE_LOW_POWER over SCAN_MODE_LOW_LATENCY for the phone BLE scanner. */
    val lowPowerScan: StateFlow<Boolean> = _lowPowerScan.asStateFlow()

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

    private companion object {
        const val FILE = "flockyou_settings"
        const val KEY_AUDIO = "audio_alerts"
        const val KEY_LOW_POWER = "low_power_scan"
    }
}
