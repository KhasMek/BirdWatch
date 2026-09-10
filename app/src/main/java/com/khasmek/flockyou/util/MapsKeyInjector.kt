package com.khasmek.flockyou.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.google.android.gms.maps.MapsInitializer

/**
 * Feeds the user-provided Google Maps API key to the Maps SDK at runtime.
 *
 * The SDK reads `com.google.android.geo.API_KEY` from the application's meta-data the first time
 * a map is created. The manifest ships an empty placeholder; this object overwrites that entry in
 * the in-process `ApplicationInfo` (both the PackageManager-cached copy and the Context's copy)
 * before any `GoogleMap` composable exists, then runs [MapsInitializer].
 *
 * Once the SDK has read a key it caches it for the life of the process, so changing the key
 * afterwards needs an app restart; [keyInUse] lets the UI say so.
 */
object MapsKeyInjector {
    private const val TAG = "FlockYou/MapsKey"
    const val META_KEY = "com.google.android.geo.API_KEY"

    /** The key the SDK was initialised with in this process, or null if no map has been set up yet. */
    @Volatile
    var keyInUse: String? = null
        private set

    val isInitialized: Boolean get() = keyInUse != null

    /** True if the SDK already holds a different key than [key] and a restart is needed. */
    fun needsRestartFor(key: String): Boolean = keyInUse != null && keyInUse != key

    /**
     * Inject [key] and initialise the SDK. Idempotent for the same key. Returns false if the
     * meta-data could not be written (the map would then use the empty placeholder and show
     * gray tiles).
     */
    fun initialize(context: Context, key: String): Boolean {
        if (keyInUse == key) return true
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
        keyInUse = key
        return true
    }
}
