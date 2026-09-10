package com.khasmek.flockyou.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.khasmek.flockyou.util.Permissions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A single GPS fix. Framework-free so it can be stamped onto detections from any source. */
data class GeoFix(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    /** Epoch millis when the fix was obtained. */
    val timestamp: Long,
)

data class LocationState(
    val fix: GeoFix? = null,
    val isTracking: Boolean = false,
    /** False when the system reports location temporarily unavailable (indoors, GPS off). */
    val isAvailable: Boolean = true,
    val error: String? = null,
) {
    val hasFix: Boolean get() = fix != null
}

/**
 * Wraps [com.google.android.gms.location.FusedLocationProviderClient] and exposes the latest fix
 * as a [StateFlow]. Detections call [currentFix] to tag themselves with the phone's position at
 * the moment of sighting. High-accuracy updates run only while a scan session is active.
 */
class LocationProvider(context: Context) {

    private val appContext = context.applicationContext
    private val client = LocationServices.getFusedLocationProviderClient(appContext)

    private val _state = MutableStateFlow(LocationState())
    val state: StateFlow<LocationState> = _state.asStateFlow()

    private var callback: LocationCallback? = null

    /** The latest fix if it is no older than [maxAgeMs], otherwise null. */
    fun currentFix(maxAgeMs: Long = DEFAULT_MAX_FIX_AGE_MS): GeoFix? {
        val fix = _state.value.fix ?: return null
        return fix.takeIf { System.currentTimeMillis() - it.timestamp <= maxAgeMs }
    }

    @SuppressLint("MissingPermission") // checked via Permissions.allEssentialGranted
    fun start() {
        if (callback != null) return
        if (!Permissions.allEssentialGranted(appContext)) {
            _state.update { it.copy(isTracking = false, error = "Location permission not granted") }
            return
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .setMinUpdateDistanceMeters(0f)
            .setWaitForAccurateLocation(false)
            .build()

        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let(::publish)
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                _state.update { it.copy(isAvailable = availability.isLocationAvailable) }
            }
        }

        try {
            client.requestLocationUpdates(request, cb, Looper.getMainLooper())
            // Seed with the last known fix so the first detection is not left untagged.
            client.lastLocation.addOnSuccessListener { loc ->
                if (loc != null && _state.value.fix == null) publish(loc)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Location permission missing", e)
            _state.update { it.copy(isTracking = false, error = "Location permission not granted") }
            return
        }

        callback = cb
        _state.update { it.copy(isTracking = true, error = null) }
        Log.i(TAG, "Location updates started")
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        client.removeLocationUpdates(cb)
        _state.update { it.copy(isTracking = false) }
        Log.i(TAG, "Location updates stopped")
    }

    private fun publish(loc: Location) {
        val fix = GeoFix(
            latitude = loc.latitude,
            longitude = loc.longitude,
            accuracyMeters = if (loc.hasAccuracy()) loc.accuracy else null,
            timestamp = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
        )
        _state.update { it.copy(fix = fix, isAvailable = true) }
    }

    companion object {
        private const val TAG = "FlockYou/Location"
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val MIN_UPDATE_INTERVAL_MS = 1_000L
        /** A fix older than this is not attached to a detection; better no GPS than a stale one. */
        const val DEFAULT_MAX_FIX_AGE_MS = 30_000L
    }
}
