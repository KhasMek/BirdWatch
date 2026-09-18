package com.khasmek.birdwatch.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.sqlite.db.SupportSQLiteDatabase
import com.khasmek.birdwatch.detection.DetectedDevice

/**
 * Room database. Enums are stored by name (Room's built-in enum support), so no TypeConverters.
 * Schema JSON is exported to `app/schemas/` and drives the auto-migrations.
 *
 * v1 -> v2: nullable Remote ID columns on `detected_devices` (uasId, operatorId, target*, operator*).
 * v2 -> v3: nullable `label` on `scan_sessions` (ESP32 imports).
 * v3 -> v4: new `device_overrides` table (per-MAC corrected position, alias, hidden flag).
 * v4 -> v5: nullable `notes` on `device_overrides`.
 * v5 -> v6: `origin` on `scan_sessions` (default LIVE), back-filled from the old default labels.
 */
@Database(
    entities = [DetectedDevice::class, ScanSession::class, DeviceOverride::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = DetectionDatabase.OriginBackfill::class),
    ],
)
abstract class DetectionDatabase : RoomDatabase() {

    abstract fun detectionDao(): DetectionDao
    abstract fun sessionDao(): SessionDao
    abstract fun deviceOverrideDao(): DeviceOverrideDao

    /**
     * Before v6 a session's provenance was only implied by the auto-generated label. Recover it
     * so old imports keep their "Imported" / "no GPS" hints once labels become user-editable.
     */
    class OriginBackfill : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE scan_sessions SET origin = CASE
                    WHEN label LIKE 'ESP32 import%' THEN 'ESP32_IMPORT'
                    WHEN label LIKE 'Imported (%'  THEN 'FILE_IMPORT'
                    WHEN label LIKE 'Restored (%'  THEN 'RESTORE'
                    ELSE 'LIVE'
                END
                """.trimIndent()
            )
        }
    }

    companion object {
        private const val NAME = "birdwatch.db"

        fun build(context: Context): DetectionDatabase =
            Room.databaseBuilder(context.applicationContext, DetectionDatabase::class.java, NAME)
                .build()
    }
}
