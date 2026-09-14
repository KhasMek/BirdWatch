package com.khasmek.birdwatch.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.google.android.gms.maps.MapsInitializer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Feeds the user-provided Google Maps API key to the Maps SDK at runtime.
 *
 * The SDK reads `com.google.android.geo.API_KEY` from the application's meta-data the first time
 * a map is created. The manifest ships an empty placeholder; this object overwrites that entry in
 * the in-process `ApplicationInfo` (both the PackageManager-cached copy and the Context's copy)
 * before any `GoogleMap` composable exists, then runs [MapsInitializer].
 *
 * Once the SDK has read a key it caches it for the life of the process, so changing the key
 * afterwards needs an app restart. [keyInUse] is therefore set exactly once per process and never
 * overwritten; the UI compares it with the stored key to say "restart required".
 */
object MapsKeyInjector {
    private const val TAG = "BirdWatch/MapsKey"
    const val META_KEY = "com.google.android.geo.API_KEY"

    private val _keyInUse = MutableStateFlow<String?>(null)

    /** The key the SDK was initialised with in this process, or null if no map has been set up yet. */
    val keyInUse: StateFlow<String?> = _keyInUse.asStateFlow()

    val isInitialized: Boolean get() = _keyInUse.value != null

    /** True if the SDK already holds a different key than [key] and a restart is needed. */
    fun needsRestartFor(key: String): Boolean = _keyInUse.value.let { it != null && it != key }

    /**
     * Inject [key] and initialise the SDK. Idempotent for the same key. If the SDK was already
     * initialised with a different key this is a no-op that returns true: the map keeps working
     * with the old key until the process restarts, and [keyInUse] keeps saying which key that is.
     * Returns false only if the meta-data could not be written or the SDK failed to initialise
     * (the map would then show gray tiles).
     */
    fun initialize(context: Context, key: String): Boolean {
        val current = _keyInUse.value
        if (current != null) {
            if (current != key) Log.i(TAG, "Maps SDK already holds a different key; restart needed for the new one")
            return true
        }
        val app = context.applicationContext
        var ok = false
        try {
            val ai = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            val bundle = ai.metaData ?: Bundle().also { ai.metaData = it }
            bundle.putString(META_KEY, key)
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "PackageManager meta-data injection failed", e)
        }
        try {
            val ctxBundle = app.applicationInfo.metaData ?: Bundle().also { app.applicationInfo.metaData = it }
            ctxBundle.putString(META_KEY, key)
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "Context meta-data injection failed", e)
        }
        if (!ok) return false

        try {
            MapsInitializer.initialize(app, MapsInitializer.Renderer.LATEST) { renderer ->
                Log.i(TAG, "Maps SDK initialised with renderer $renderer")
            }
        } catch (e: Exception) {
            Log.e(TAG, "MapsInitializer failed", e)
            return false
        }
        _keyInUse.value = key
        return true
    }
}
