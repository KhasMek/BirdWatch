package com.khasmek.birdwatch.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Secrets the user types in, held in [EncryptedSharedPreferences] (AES-256, Android Keystore
 * master key). Currently just the user's own Google Maps API key, which release builds no longer
 * need (they carry a built-in key via BuildConfig); it remains the way a debug build or a fork
 * without a key gets a map. See [com.khasmek.birdwatch.util.MapsKeyInjector] for how whichever
 * key applies reaches the Maps SDK at runtime.
 *
 * Opening the encrypted store touches the Keystore, so it happens on IO once; [isLoaded] flips
 * when the initial read is done so screens can avoid a "no key" flash.
 */
// security-crypto 1.1.0 marks EncryptedSharedPreferences deprecated (Google stopped maintaining the
// library) but offers no drop-in replacement. It still works and is the right tool for one secret.
@Suppress("DEPRECATION")
class SecureSettings(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy { openPrefs() }

    private fun createPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * The key set inside the prefs file is wrapped by a Keystore key that never leaves the device
     * it was made on. If the file cannot be opened (app data copied from another phone, a wiped
     * Keystore), it is useless: throw it away and start with an empty store rather than failing
     * every later write. The user just re-enters the Maps key.
     */
    private fun openPrefs(): SharedPreferences = try {
        createPrefs()
    } catch (e: Exception) {
        Log.w(TAG, "Encrypted settings could not be opened; resetting them", e)
        appContext.deleteSharedPreferences(FILE)
        createPrefs()
    }

    private val _mapsApiKey = MutableStateFlow<String?>(null)
    /** Trimmed key, or null when none is stored. */
    val mapsApiKey: StateFlow<String?> = _mapsApiKey.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            try {
                _mapsApiKey.value = prefs.getString(KEY_MAPS, null)?.takeIf { it.isNotBlank() }
            } catch (e: Exception) {
                Log.e(TAG, "Could not open encrypted settings", e)
            }
            _isLoaded.value = true
        }
    }

    /** Persist (or clear, when null/blank). Throws if the encrypted store is unusable even after a reset. */
    suspend fun setMapsApiKey(key: String?) = withContext(Dispatchers.IO) {
        val clean = key?.trim()?.takeIf { it.isNotEmpty() }
        prefs.edit { if (clean == null) remove(KEY_MAPS) else putString(KEY_MAPS, clean) }
        _mapsApiKey.value = clean
    }

    companion object {
        private const val TAG = "BirdWatch/SecureSettings"
        private const val FILE = "birdwatch_secure"
        private const val KEY_MAPS = "google_maps_api_key"

        /**
         * Cheap sanity check before saving: Google API keys are 39 characters starting with
         * "AIza". This cannot tell whether the key is enabled for the Maps SDK for Android; only
         * loading a map does (gray tiles = bad key).
         */
        fun looksLikeGoogleApiKey(key: String): Boolean {
            val k = key.trim()
            return k.length == 39 && k.startsWith("AIza") && k.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        }
    }
}
