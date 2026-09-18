package com.khasmek.birdwatch.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * One breadcrumb of a device's signal trail: where the phone was and how strong the signal was
 * at one sighting. Kept only for tracked devices (per-device `DeviceOverride.track`, or
 * `AppSettings.trackAllSightings`), throttled by [SightingTrail.shouldSample] and capped per
 * device per session, so a session's trail data stays in the tens of kilobytes. Schema v7.
 */
@Entity(
    tableName = "sighting_samples",
    indices = [Index("sessionId", "macAddress"), Index("macAddress")],
)
data class SightingSample(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val macAddress: String,
    /** Epoch millis of the sighting. */
    val time: Long,
    val latitude: Double,
    val longitude: Double,
    val rssi: Int,
)

@Dao
interface SightingSampleDao {

    @Insert
    suspend fun insertAll(samples: List<SightingSample>)

    @Query("SELECT * FROM sighting_samples WHERE macAddress = :macAddress AND sessionId IN (:sessionIds) ORDER BY time")
    fun observeForDevice(macAddress: String, sessionIds: List<String>): Flow<List<SightingSample>>

    @Query("SELECT * FROM sighting_samples WHERE macAddress = :macAddress AND sessionId IN (:sessionIds) ORDER BY time")
    suspend fun getForDevice(macAddress: String, sessionIds: List<String>): List<SightingSample>

    @Query("SELECT * FROM sighting_samples WHERE sessionId = :sessionId ORDER BY macAddress, time")
    suspend fun getBySession(sessionId: String): List<SightingSample>

    @Query("SELECT * FROM sighting_samples ORDER BY sessionId, macAddress, time")
    suspend fun getAll(): List<SightingSample>

    @Query("SELECT COUNT(*) FROM sighting_samples WHERE sessionId = :sessionId AND macAddress = :macAddress")
    suspend fun count(sessionId: String, macAddress: String): Int

    @Query("SELECT COUNT(*) FROM sighting_samples")
    suspend fun countAll(): Int

    @Query("DELETE FROM sighting_samples WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    @Query("DELETE FROM sighting_samples WHERE macAddress = :macAddress AND sessionId IN (:sessionIds)")
    suspend fun deleteForDevice(macAddress: String, sessionIds: List<String>)

    @Query("DELETE FROM sighting_samples")
    suspend fun deleteAll(): Int
}

/** Pure trail arithmetic: when to keep a sample, and where the samples say the device is. */
object SightingTrail {

    const val MIN_INTERVAL_MS = 5_000L
    const val MIN_DISTANCE_M = 15.0
    const val MAX_PER_DEVICE_PER_SESSION = 200

    /**
     * Keep a new sighting at ([lat], [lon], [time]) given the previous kept sample? True when
     * there is none, or enough time or distance has passed. Callers also enforce the cap.
     */
    fun shouldSample(prevTime: Long?, prevLat: Double?, prevLon: Double?, time: Long, lat: Double, lon: Double): Boolean {
        if (prevTime == null || prevLat == null || prevLon == null) return true
        if (time - prevTime >= MIN_INTERVAL_MS) return true
        return distanceMeters(prevLat, prevLon, lat, lon) >= MIN_DISTANCE_M
    }

    /**
     * Where the device probably is: the samples' position weighted by signal strength, so the
     * strong readings near the device pull the estimate toward it and the faint ones at the
     * edge of range barely count. Null with fewer than [minSamples] points.
     */
    fun suggestedPosition(samples: List<SightingSample>, minSamples: Int = 3): Pair<Double, Double>? {
        if (samples.size < minSamples) return null
        var wSum = 0.0; var lat = 0.0; var lon = 0.0
        for (s in samples) {
            // -100 dBm and below count almost nothing; -40 dBm counts 3600x more than -99.
            val w = (s.rssi + 100).coerceAtLeast(1).toDouble().let { it * it }
            wSum += w; lat += s.latitude * w; lon += s.longitude * w
        }
        return (lat / wSum) to (lon / wSum)
    }

    /** Equirectangular approximation; plenty for a few hundred metres. */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1) * cos(Math.toRadians((lat1 + lat2) / 2))
        return sqrt(dLat * dLat + dLon * dLon) * EARTH_RADIUS_M
    }

    private const val EARTH_RADIUS_M = 6_371_000.0
}
