package com.khasmek.birdwatch.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DeviceType
import kotlinx.coroutines.flow.Flow

@Dao
interface DetectionDao {

    /** Insert-or-update keyed on (sessionId, macAddress). */
    @Upsert
    suspend fun upsert(device: DetectedDevice)

    @Upsert
    suspend fun upsertAll(devices: List<DetectedDevice>)

    @Query("SELECT * FROM detected_devices WHERE sessionId = :sessionId ORDER BY lastSeen DESC")
    fun observeBySession(sessionId: String): Flow<List<DetectedDevice>>

    @Query("SELECT * FROM detected_devices WHERE sessionId = :sessionId ORDER BY lastSeen DESC")
    suspend fun getBySession(sessionId: String): List<DetectedDevice>

    @Query("SELECT * FROM detected_devices ORDER BY lastSeen DESC")
    fun observeAll(): Flow<List<DetectedDevice>>

    @Query("SELECT * FROM detected_devices ORDER BY lastSeen DESC")
    suspend fun getAll(): List<DetectedDevice>

    @Query("SELECT COUNT(*) FROM detected_devices WHERE sessionId = :sessionId")
    fun observeCount(sessionId: String): Flow<Int>

    @Query("DELETE FROM detected_devices WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: String)

    /** Remove one device's rows from the given sessions. Returns how many rows went. */
    @Query("DELETE FROM detected_devices WHERE macAddress = :macAddress AND sessionId IN (:sessionIds)")
    suspend fun deleteDevice(macAddress: String, sessionIds: List<String>): Int

    @Query("DELETE FROM detected_devices")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM detected_devices")
    suspend fun count(): Int

    /** Devices per type across all sessions (the backup picker groups these into categories). */
    @Query("SELECT deviceType, COUNT(*) AS count FROM detected_devices GROUP BY deviceType")
    suspend fun countByType(): List<TypeCount>
}

data class TypeCount(val deviceType: DeviceType, val count: Int)
