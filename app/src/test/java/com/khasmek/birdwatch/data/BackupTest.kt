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
    fun `file name is timestamped`() {
        assertEquals("birdwatch_backup_19700101T000000Z.json", BackupWriter.fileName(ExportFormat.JSON, 0L))
    }

    @Test
    fun `garbage is rejected`() {
        assertThrows(ImportFormatException::class.java) { BackupReader.parse("hello") }
        assertThrows(ImportFormatException::class.java) { BackupReader.parse("""{"kind":"backup"}""") }
    }
}
