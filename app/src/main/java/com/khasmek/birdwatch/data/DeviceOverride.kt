package com.khasmek.birdwatch.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * What the user has said about a physical device, keyed by MAC so it applies to every session
 * that saw it. A fixed camera or Raven keeps its MAC, so one correction fixes every duplicate
 * pin. The detected rows are never altered: the corrected position lives here, the observed
 * one stays in `detected_devices`.
 *
 * Schema v4. A row with nothing set is equivalent to no row; callers delete it then.
 */
@Entity(tableName = "device_overrides")
data class DeviceOverride(
    /** Normalised MAC (uppercase, colons), same key as `detected_devices.macAddress`. */
    @PrimaryKey val macAddress: String,
    /** Corrected pin position, or null to keep the detected one. */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** User-chosen name shown instead of the detected name / MAC. */
    val alias: String? = null,
    /** Keep the data, drop the pin. */
    val hidden: Boolean = false,
    val updatedAt: Long = 0L,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null

    /** True when every field is at its default, i.e. the row carries no information. */
    val isEmpty: Boolean get() = !hasLocation && alias.isNullOrBlank() && !hidden
}

@Dao
interface DeviceOverrideDao {

    @Query("SELECT * FROM device_overrides")
    fun observeAll(): Flow<List<DeviceOverride>>

    @Query("SELECT * FROM device_overrides")
    suspend fun getAll(): List<DeviceOverride>

    @Query("SELECT * FROM device_overrides WHERE macAddress = :macAddress")
    suspend fun get(macAddress: String): DeviceOverride?

    @Upsert
    suspend fun upsert(override: DeviceOverride)

    @Upsert
    suspend fun upsertAll(overrides: List<DeviceOverride>)

    @Query("DELETE FROM device_overrides WHERE macAddress = :macAddress")
    suspend fun delete(macAddress: String)

    @Query("DELETE FROM device_overrides")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM device_overrides")
    suspend fun count(): Int
}
