package com.khasmek.birdwatch.detection

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Thread-safe in-memory table of unique devices keyed by MAC, shared by every detection source.
 * Mirrors the firmware's `fyAddDetection()`: first sighting inserts, later sightings merge.
 *
 * Exposes the full list (most recently seen first) as a [StateFlow] and a one-shot event per new
 * MAC as a [SharedFlow], which drive persistence and audio alerts. No Android dependencies.
 */
class DetectionTable {

    private val lock = Any()
    private val byMac = LinkedHashMap<String, DetectedDevice>()

    private val _devices = MutableStateFlow<List<DetectedDevice>>(emptyList())
    val devices: StateFlow<List<DetectedDevice>> = _devices.asStateFlow()

    private val _newDetections = MutableSharedFlow<DetectedDevice>(extraBufferCapacity = 64)
    val newDetections: SharedFlow<DetectedDevice> = _newDetections.asSharedFlow()

    val size: Int get() = synchronized(lock) { byMac.size }

    /** Current rows without waiting on the flow. */
    fun snapshot(): List<DetectedDevice> = synchronized(lock) { byMac.values.toList() }

    fun clear() {
        synchronized(lock) { byMac.clear() }
        _devices.value = emptyList()
    }

    /**
     * Insert-or-merge. [create] is called only when [macAddress] is unseen; [merge] receives the
     * existing row otherwise. Returns the stored row and whether it was newly created. A new row
     * is also emitted on [newDetections].
     */
    fun upsert(
        macAddress: String,
        create: () -> DetectedDevice,
        merge: (existing: DetectedDevice) -> DetectedDevice,
    ): Pair<DetectedDevice, Boolean> {
        val key = normalizeMac(macAddress)
        val stored: DetectedDevice
        val isNew: Boolean
        synchronized(lock) {
            val existing = byMac[key]
            isNew = existing == null
            stored = if (existing == null) create() else merge(existing)
            byMac[key] = stored
            _devices.value = byMac.values.sortedByDescending { it.lastSeen }
        }
        if (isNew) _newDetections.tryEmit(stored)
        return stored to isNew
    }

    companion object {
        /** MACs are stored uppercase with colons so BLE ("F8:4D:...") and firmware ("f8:4d:...") agree. */
        fun normalizeMac(mac: String): String = mac.trim().uppercase(Locale.ROOT)
    }
}
