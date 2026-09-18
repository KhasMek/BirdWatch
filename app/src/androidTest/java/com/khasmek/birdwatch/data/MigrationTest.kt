package com.khasmek.birdwatch.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every schema version a user could be on must migrate to the current one without losing a row.
 * The exported schemas in app/schemas are the test assets; each test creates a database at an old
 * version with hand-written rows, migrates it forward through every auto-migration, and checks
 * the data (including the v6 origin back-fill) came through. Runs on a device.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        DetectionDatabase::class.java,
        listOf(DetectionDatabase.OriginBackfill()),
    )

    private val latest = 7

    @Test
    fun v1_to_latest_keeps_devices_and_sessions() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt) VALUES ('s1', 1000, 2000)")
            db.execSQL(
                """INSERT INTO detected_devices (sessionId, macAddress, source, deviceName, detectionMethod, deviceType,
                   confidence, matchedOn, ravenFirmware, tier, channel, rssi, latitude, longitude, accuracyMeters,
                   firstSeen, lastSeen, sightings)
                   VALUES ('s1', 'D4:11:D6:AA:BB:CC', 'BLE', 'Raven', 'raven_uuid', 'RAVEN', 'HIGH', '00003100', '1.3.x',
                   NULL, NULL, -70, 37.1, -122.1, 5.0, 1100, 1900, 7)"""
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, latest, true).use { db ->
            db.query("SELECT sightings, uasId, deviceName FROM detected_devices WHERE macAddress = 'D4:11:D6:AA:BB:CC'").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(7, c.getInt(0))
                assertTrue(c.isNull(1)) // v2 column, null for an old row
                assertEquals("Raven", c.getString(2))
            }
            db.query("SELECT label, origin FROM scan_sessions WHERE id = 's1'").use { c ->
                assertTrue(c.moveToFirst())
                assertTrue(c.isNull(0))
                assertEquals("LIVE", c.getString(1))
            }
            assertEquals(0, db.count("device_overrides"))
            assertEquals(0, db.count("sighting_samples"))
        }
    }

    @Test
    fun v3_to_latest_backfills_origin_from_the_old_default_labels() {
        helper.createDatabase(TEST_DB, 3).use { db ->
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label) VALUES ('live', 1, 2, NULL)")
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label) VALUES ('esp', 1, 2, 'ESP32 import (flash)')")
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label) VALUES ('file', 1, 2, 'Imported (JSON)')")
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label) VALUES ('rest', 1, 2, 'Restored (CSV)')")
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label) VALUES ('named', 1, 2, 'Downtown loop')")
        }
        helper.runMigrationsAndValidate(TEST_DB, latest, true).use { db ->
            val origins = mutableMapOf<String, String>()
            db.query("SELECT id, origin FROM scan_sessions").use { c -> while (c.moveToNext()) origins[c.getString(0)] = c.getString(1) }
            assertEquals(
                mapOf("live" to "LIVE", "esp" to "ESP32_IMPORT", "file" to "FILE_IMPORT", "rest" to "RESTORE", "named" to "LIVE"),
                origins,
            )
            // Labels are untouched by the back-fill.
            db.query("SELECT label FROM scan_sessions WHERE id = 'esp'").use { c -> c.moveToFirst(); assertEquals("ESP32 import (flash)", c.getString(0)) }
        }
    }

    @Test
    fun v4_to_latest_keeps_overrides_and_defaults_the_new_columns() {
        helper.createDatabase(TEST_DB, 4).use { db ->
            db.execSQL(
                "INSERT INTO device_overrides (macAddress, latitude, longitude, alias, hidden, updatedAt) " +
                    "VALUES ('82:6B:F2:14:07:3A', 37.2, -122.9, 'North gate', 0, 5000)"
            )
        }
        helper.runMigrationsAndValidate(TEST_DB, latest, true).use { db ->
            db.query("SELECT alias, latitude, notes, track FROM device_overrides").use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("North gate", c.getString(0))
                assertEquals(37.2, c.getDouble(1), 1e-9)
                assertTrue(c.isNull(2))   // v5 notes
                assertEquals(0, c.getInt(3)) // v7 track defaults to false
            }
        }
    }

    @Test
    fun v6_to_latest_adds_the_samples_table_the_dao_can_use() = runBlocking {
        helper.createDatabase(TEST_DB, 6).use { db ->
            db.execSQL("INSERT INTO scan_sessions (id, startedAt, endedAt, label, origin) VALUES ('s1', 1, 2, NULL, 'LIVE')")
        }
        helper.runMigrationsAndValidate(TEST_DB, latest, true).close()

        // Open the migrated file with Room itself and exercise the new DAO end to end.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = Room.databaseBuilder(context, DetectionDatabase::class.java, TEST_DB).build()
        try {
            val dao = room.sightingSampleDao()
            dao.insertAll(listOf(SightingSample(sessionId = "s1", macAddress = "AA:BB", time = 10, latitude = 1.0, longitude = 2.0, rssi = -60)))
            assertEquals(1, dao.count("s1", "AA:BB"))
            assertEquals(1, dao.getForDevice("AA:BB", listOf("s1")).size)
            assertNull(room.deviceOverrideDao().get("AA:BB"))
            assertEquals(1, room.sessionDao().count())
        } finally {
            room.close()
        }
    }

    @Test
    fun a_fresh_latest_database_matches_the_exported_schema() {
        // createDatabase at the latest version validates the exported 7.json against the entities;
        // a schema edit without a version bump fails here before it reaches a user.
        helper.createDatabase(TEST_DB, latest).close()
        helper.runMigrationsAndValidate(TEST_DB, latest, true).close()
    }

    private fun SupportSQLiteDatabase.count(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { c -> c.moveToFirst(); c.getInt(0) }

    private companion object {
        const val TEST_DB = "migration-test.db"
    }
}
