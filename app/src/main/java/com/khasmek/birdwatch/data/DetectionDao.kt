package com.khasmek.birdwatch.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.khasmek.birdwatch.detection.DetectedDevice
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

    @Query("DELETE FROM detected_devices")
    suspend fun deleteAll()
}
