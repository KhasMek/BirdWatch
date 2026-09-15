package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionMethod
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DeviceCategory
import com.khasmek.birdwatch.detection.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private fun dev(session: String, mac: String, type: DeviceType, method: DetectionMethod, t: Long) = DetectedDevice(
        sessionId = session, macAddress = mac, source = DetectionSource.BLE, deviceName = null,
        detectionMethod = method, deviceType = type, confidence = Confidence.HIGH, matchedOn = "x",
        rssi = -60, latitude = 37.1, longitude = -122.1, accuracyMeters = 5f, firstSeen = t, lastSeen = t + 1000, sightings = 2,
    )

    private val s1 = ScanSession("s1", 1_000_000L, 1_200_000L, label = null)
    private val s2 = ScanSession("s2", 2_000_000L, 2_300_000L, label = "ESP32 import (flash)")
    private val flock1 = dev("s1", "58:8E:81:00:00:01", DeviceType.FLOCK, DetectionMethod.MAC_PREFIX, 1_010_000L)
    private val raven1 = dev("s1", "D4:11:D6:00:00:01", DeviceType.RAVEN, DetectionMethod.RAVEN_UUID, 1_020_000L)
    private val axon2 = dev("s2", "00:25:DF:00:00:02", DeviceType.AXON, DetectionMethod.BLE_COMPANY_ID, 2_010_000L)
    private val bundles = listOf(SessionBundle(s1, listOf(flock1, raven1)), SessionBundle(s2, listOf(axon2)))
    private val allCats = DeviceCategory.entries.toSet()

    @Test
    fun `json backup round-trips sessions, labels and devices`() {
        val text = BackupWriter.json(bundles, allCats, 3_000_000L)
        val back = BackupReader.parse(text, "b.json")
        assertEquals(ExportFormat.JSON, back.format)
        assertEquals(2, back.sessions.size)
        val bySession = back.sessions.associateBy { it.session.id }
        assertEquals(s1, bySession["s1"]!!.session)
        assertEquals(s2, bySession["s2"]!!.session) // label survives
        assertEquals(listOf(flock1, raven1).sortedBy { it.macAddress }, bySession["s1"]!!.devices.sortedBy { it.macAddress })
        assertEquals(listOf(axon2), bySession["s2"]!!.devices)
        assertEquals(mapOf(DeviceCategory.FLOCK_ALPR to 1, DeviceCategory.GUNSHOT_DETECTOR to 1, DeviceCategory.LAW_ENFORCEMENT to 1), back.categoryCounts)
    }

    @Test
    fun `csv backup round-trips several sessions`() {
        val back = BackupReader.parse(BackupWriter.csv(bundles), "b.csv")
        assertEquals(ExportFormat.CSV, back.format)
        assertEquals(setOf("s1", "s2"), back.sessions.map { it.session.id }.toSet())
        assertEquals(3, back.deviceCount)
        val s1b = back.sessions.first { it.session.id == "s1" }
        assertEquals(flock1.firstSeen, s1b.session.startedAt) // bounded by detections; label lost
        assertEquals(raven1.lastSeen, s1b.session.endedAt)
    }

    @Test
    fun `filtering drops categories and empties sessions`() {
        val back = BackupReader.parse(BackupWriter.json(bundles, allCats, 0L))
        val onlyFlock = back.filtered(setOf(DeviceCategory.FLOCK_ALPR))
        assertEquals(1, onlyFlock.sessions.size)
        assertEquals(listOf(flock1), onlyFlock.sessions[0].devices)
        assertEquals(0, back.filtered(setOf(DeviceCategory.DRONE)).sessions.size)
    }

    @Test
    fun `single-session export is accepted by the backup reader`() {
        val text = ExportWriter.json(s1, listOf(flock1, raven1), 0L)
        val back = BackupReader.parse(text)
        assertEquals(1, back.sessions.size)
        assertEquals("s1", back.sessions[0].session.id)
        assertEquals(2, back.deviceCount)
    }

    @Test
    fun `kml backup has one folder per session and is not restorable`() {
        val kml = BackupWriter.kml(bundles, 0L)
        assertEquals(2, Regex("<Folder>").findAll(kml).count())
        assertTrue(kml.contains("<name>ESP32 import (flash)</name>"))
        assertEquals(3, Regex("<Placemark>").findAll(kml).count())
        assertThrows(ImportFormatException::class.java) { BackupReader.parse(kml) }
    }

    @Test
    fun `csv backup keeps multi-line names in the right session`() {
        val nasty = flock1.copy(deviceName = "cam\n\"two\"")
        val back = BackupReader.parse(BackupWriter.csv(listOf(SessionBundle(s1, listOf(nasty, raven1)), SessionBundle(s2, listOf(axon2)))))
        assertEquals(2, back.sessions.size)
        assertEquals(nasty, back.sessions.first { it.session.id == "s1" }.devices.first { it.macAddress == nasty.macAddress })
    }

    @Test
    fun `merge never regresses a row that kept scanning after the backup`() {
        val backedUp = flock1.copy(sightings = 5, lastSeen = 1_100_000L, rssi = -80, latitude = 1.0, longitude = 1.0, tier = 2)
        val local = flock1.copy(sightings = 500, lastSeen = 1_900_000L, rssi = -40, latitude = 2.0, longitude = 2.0, tier = 4, deviceName = "learned")
        val merged = mergeDetection(local, backedUp)
        assertEquals(local, merged) // newer, more sightings, higher tier, has a name: nothing to take
        // Symmetric: restoring a newer record onto an older local row takes the newer fields.
        val fromNewerBackup = mergeDetection(backedUp, local)
        assertEquals(local.copy(firstSeen = minOf(local.firstSeen, backedUp.firstSeen)), fromNewerBackup)
    }

    @Test
    fun `merge combines span, name and tier from both sides`() {
        val local = flock1.copy(firstSeen = 1_000L, lastSeen = 5_000L, sightings = 3, deviceName = null, tier = 4)
        val incoming = flock1.copy(firstSeen = 500L, lastSeen = 4_000L, sightings = 9, deviceName = "old name", tier = 2, rssi = -99)
        val merged = mergeDetection(local, incoming)
        assertEquals(500L, merged.firstSeen)
        assertEquals(5_000L, merged.lastSeen)
        assertEquals(9, merged.sightings)
        assertEquals("old name", merged.deviceName)
        assertEquals(4, merged.tier)
        assertEquals(local.rssi, merged.rssi) // local is the more recent sighting
    }

    @Test
    fun `device edits travel with a backup, one per mac, newest wins, dropped with their category`() {
        val sameMacInS2 = flock1.copy(sessionId = "s2")
        val bundlesWithDup = listOf(SessionBundle(s1, listOf(flock1, raven1)), SessionBundle(s2, listOf(axon2, sameMacInS2)))
        val flockEdit = DeviceOverride(flock1.macAddress, latitude = 1.5, longitude = 2.5, alias = "North gate", updatedAt = 9_000L)
        val axonEdit = DeviceOverride(axon2.macAddress, hidden = true, updatedAt = 8_000L)
        val overrides = mapOf(flockEdit.macAddress to flockEdit, axonEdit.macAddress to axonEdit)

        for (text in listOf(BackupWriter.json(bundlesWithDup, allCats, 0L, overrides), BackupWriter.csv(bundlesWithDup, overrides))) {
            val back = BackupReader.parse(text)
            assertEquals(setOf(flockEdit, axonEdit), back.overrides.toSet()) // the duplicate MAC collapsed to one
            // Detected positions unchanged on every row that carried the edit.
            back.sessions.flatMap { it.devices }.filter { it.macAddress == flock1.macAddress }.forEach {
                assertEquals(flock1.latitude!!, it.latitude!!, 1e-6)
            }
            // Filtering out a category also drops that device's edit.
            val flockOnly = back.filtered(setOf(DeviceCategory.FLOCK_ALPR))
            assertEquals(listOf(flockEdit), flockOnly.overrides)
        }
    }

    @Test
    fun `file name is timestamped`() {
        assertEquals("birdwatch_backup_19700101T000000Z.json", BackupWriter.fileName(ExportFormat.JSON, 0L))
    }

    @Test
    fun `garbage is rejected`() {
        assertThrows(ImportFormatException::class.java) { BackupReader.parse("hello") }
        assertThrows(ImportFormatException::class.java) { BackupReader.parse("""{"kind":"backup"}""") }
    }
}
