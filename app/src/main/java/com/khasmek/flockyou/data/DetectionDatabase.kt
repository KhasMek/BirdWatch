package com.khasmek.flockyou.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.khasmek.flockyou.detection.DetectedDevice

/**
 * Room database. Enums are stored by name (Room's built-in enum support), so no TypeConverters.
 * Schema JSON is exported to `app/schemas/` and drives the auto-migrations.
 *
 * v1 -> v2: nullable Remote ID columns on `detected_devices` (uasId, operatorId, target*, operator*).
 */
@Database(
    entities = [DetectedDevice::class, ScanSession::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class DetectionDatabase : RoomDatabase() {

    abstract fun detectionDao(): DetectionDao
    abstract fun sessionDao(): SessionDao

    companion object {
        private const val NAME = "flockyou.db"

        fun build(context: Context): DetectionDatabase =
            Room.databaseBuilder(context.applicationContext, DetectionDatabase::class.java, NAME)
                .build()
    }
}
