package com.khasmek.birdwatch.ui.screens

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.khasmek.birdwatch.AppContainer
import com.khasmek.birdwatch.data.SecureSettings
import com.khasmek.birdwatch.detection.PackId
import com.khasmek.birdwatch.detection.SignaturePack
import com.khasmek.birdwatch.detection.SignaturePacks
import com.khasmek.birdwatch.util.MapsKeyInjector
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val loaded: Boolean = false,
    /** Stored key, or null. Never shown in full by the UI unless the user toggles visibility. */
    val mapsApiKey: String? = null,
    val audioAlerts: Boolean = true,
    val lowPowerScan: Boolean = false,
    /** A different key is already in use by the Maps SDK; the new one applies after restart. */
    val restartRequired: Boolean = false,
    val enabledPacks: Set<PackId> = emptySet(),
    val wifiApScan: Boolean = false,
) {
    val hasKey: Boolean get() = !mapsApiKey.isNullOrBlank()
    val keyHint: String? get() = mapsApiKey?.let { "…" + it.takeLast(4) }
    val packs: List<SignaturePack> get() = SignaturePacks.OPTIONAL
}

sealed interface SaveResult {
    data object Saved : SaveResult
    data object Cleared : SaveResult
    data class Rejected(val reason: String) : SaveResult
}

class SettingsViewModel(private val container: AppContainer) : ViewModel() {

    private val settings = container.settings
    private val secure = container.secureSettings

    val uiState: StateFlow<SettingsUiState> = combine(
        secure.isLoaded,
        secure.mapsApiKey,
        settings.audioAlerts,
        settings.lowPowerScan,
        settings.enabledPacks,
    ) { loaded, key, audio, lowPower, packs ->
        SettingsUiState(
            loaded = loaded,
            mapsApiKey = key,
            audioAlerts = audio,
            lowPowerScan = lowPower,
            enabledPacks = packs,
        )
    }.combine(settings.wifiApScan) { s, wifi -> s.copy(wifiApScan = wifi) }
        // Reactive, so the hint appears the moment a different key is saved and never disappears
        // while the SDK still holds the old one.
        .combine(MapsKeyInjector.keyInUse) { s, inUse -> s.copy(restartRequired = s.mapsApiKey != null && inUse != null && inUse != s.mapsApiKey) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    /** Validate format and persist. Returns the outcome for the screen to toast. */
    fun saveMapsApiKey(input: String, onResult: (SaveResult) -> Unit) {
        val key = input.trim()
        if (key.isEmpty()) {
            clearMapsApiKey(onResult)
            return
        }
        if (!SecureSettings.looksLikeGoogleApiKey(key)) {
            onResult(SaveResult.Rejected("That doesn't look like a Google API key (39 characters, starts with AIza)."))
            return
        }
        store(key, SaveResult.Saved, onResult)
    }

    fun clearMapsApiKey(onResult: (SaveResult) -> Unit) = store(null, SaveResult.Cleared, onResult)

    /** The encrypted store can fail (Keystore trouble); report it instead of crashing the app. */
    private fun store(key: String?, success: SaveResult, onResult: (SaveResult) -> Unit) {
        viewModelScope.launch {
            val result = runCatching { secure.setMapsApiKey(key) }
                .fold(onSuccess = { success }, onFailure = { e ->
                    Log.e(TAG, "Could not write the Maps key", e)
                    SaveResult.Rejected("Couldn't save the key: ${e.message ?: e::class.simpleName}")
                })
            onResult(result)
        }
    }

    fun setAudioAlerts(enabled: Boolean) = settings.setAudioAlerts(enabled)
    fun setLowPowerScan(enabled: Boolean) = settings.setLowPowerScan(enabled)
    fun setPackEnabled(pack: PackId, enabled: Boolean) = settings.setPackEnabled(pack, enabled)
    fun setWifiApScan(enabled: Boolean) = settings.setWifiApScan(enabled)
    /** False if the tones are not ready yet (they are synthesised on first use). */
    fun playTestChirp(): Boolean = container.alertSounds.playTest()

    companion object {
        private const val TAG = "BirdWatch/Settings"
        const val HELP_URL = "https://developers.google.com/maps/documentation/android-sdk/get-api-key"
    }
}
