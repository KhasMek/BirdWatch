package com.khasmek.birdwatch.data

import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectedDevice
import com.khasmek.birdwatch.detection.DetectionMethod
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DeviceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportReaderTest {

    private val session = ScanSession(id = "abcdef12-0000-0000-0000-000000000000", startedAt = 1_789_071_969_381L, endedAt = 1_789_071_997_367L)

    private val flock = DetectedDevice(
        sessionId = session.id, macAddress = "82:6B:F2:14:07:3A", source = DetectionSource.ESP32_WIFI,
        deviceName = null, detectionMethod = DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, deviceType = DeviceType.FLOCK,
        confidence = Confidence.HIGH, matchedOn = "82:6b:f2", tier = 4, channel = 6, rssi = -52,
        latitude = 37.123456, longitude = -122.987654, accuracyMeters = 4.6f,
        firstSeen = 1_789_071_970_000L, lastSeen = 1_789_071_990_000L, sightings = 12,
    )
    private val raven = DetectedDevice(
        sessionId = session.id, macAddress = "D4:11:D6:AA:BB:CC", source = DetectionSource.BLE,
        deviceName = "Raven, \"unit 7\"", detectionMethod = DetectionMethod.RAVEN_UUID, deviceType = DeviceType.RAVEN,
        confidence = Confidence.HIGH, matchedOn = "00003100-0000-1000-8000-00805f9b34fb", ravenFirmware = "1.3.x",
        rssi = -70, firstSeen = 1_789_071_971_000L, lastSeen = 1_789_071_972_000L, sightings = 1,
    )
    private val drone = DetectedDevice(
        sessionId = session.id, macAddress = "7A:00:00:00:00:01", source = DetectionSource.BLE, deviceName = null,
        detectionMethod = DetectionMethod.REMOTE_ID_BLE, deviceType = DeviceType.REMOTE_ID_UAS, confidence = Confidence.HIGH,
        matchedOn = "SER1", rssi = -60, firstSeen = 1_789_071_975_000L, lastSeen = 1_789_071_980_000L, sightings = 3,
        uasId = "SER1", operatorId = "FIN87", targetLatitude = 37.0, targetLongitude = -122.0, targetAltitudeM = 120.0,
        operatorLatitude = 37.001, operatorLongitude = -122.001,
    )
    private val all = listOf(flock, raven, drone)

    @Test
    fun `json export round-trips exactly`() {
        val text = ExportWriter.json(session, all, exportedAt = 1_789_072_000_000L)
        val back = ExportReader.parse(text, "x.json")
        assertEquals(ExportFormat.JSON, back.format)
        assertEquals(session, back.session)
        assertEquals(all.sortedBy { it.macAddress }, back.devices.sortedBy { it.macAddress })
    }

    @Test
    fun `csv export round-trips with session bounded by detections`() {
        val text = ExportWriter.csv(all)
        val back = ExportReader.parse(text, "x.csv")
        assertEquals(ExportFormat.CSV, back.format)
        assertEquals(session.id, back.session.id)
        assertEquals(all.minOf { it.firstSeen }, back.session.startedAt)
        assertEquals(all.maxOf { it.lastSeen }, back.session.endedAt)
        // CSV rounds coordinates to 6 decimals and accuracy to 1; everything else is exact.
        val got = back.devices.associateBy { it.macAddress }
        assertEquals(raven, got[raven.macAddress])
        val f = got[flock.macAddress]!!
        assertEquals(flock.copy(latitude = f.latitude, longitude = f.longitude, accuracyMeters = f.accuracyMeters), f)
        assertEquals(37.123456, f.latitude!!, 1e-6)
        assertEquals(4.6f, f.accuracyMeters!!, 0.05f)
        val d = got[drone.macAddress]!!
        assertEquals("SER1", d.uasId); assertEquals("FIN87", d.operatorId)
        assertEquals(120.0, d.targetAltitudeM!!, 0.1)
    }

    @Test
    fun `csv cells with line breaks, commas and quotes survive the round trip`() {
        val nasty = raven.copy(deviceName = "Line one\nline \"two\", still\r\nthree", matchedOn = "a,b")
        val back = ExportReader.parse(ExportWriter.csv(listOf(flock, nasty)), "x.csv")
        assertEquals(2, back.devices.size)
        assertEquals(nasty, back.devices.first { it.macAddress == nasty.macAddress })
    }

    @Test
    fun `csv records tokenizer handles CRLF, blank lines and a missing trailing newline`() {
        val records = ExportReader.parseCsvRecords("a,b\r\n\r\n\"x\r\ny\",\"q\"\"q\"\n\nlast,row")
        assertEquals(listOf(listOf("a", "b"), listOf("x\r\ny", "q\"q"), listOf("last", "row")), records)
    }

    @Test
    fun `session label survives a json round trip`() {
        val labelled = session.copy(label = "ESP32 import (flash)")
        val back = ExportReader.parse(ExportWriter.json(labelled, listOf(flock), 0L))
        assertEquals("ESP32 import (flash)", back.session.label)
    }

    @Test
    fun `unknown enum spellings fall back instead of failing`() {
        val text = ExportWriter.json(session, listOf(flock), 0L)
            .replace("\"WIFI_WILDCARD_PROBE_IE_SIG\"", "\"wifi_wildcard_probe_ie_sig\"") // wire name spelling
            .replace("\"ESP32_WIFI\"", "\"FUTURE_SOURCE\"")
        val back = ExportReader.parse(text)
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, back.devices[0].detectionMethod)
        assertEquals(DetectionSource.BLE, back.devices[0].source)
    }

    @Test
    fun `rejects kml, foreign json, foreign csv and garbage with readable messages`() {
        assertThrows(ImportFormatException::class.java) { ExportReader.parse(ExportWriter.kml(session, all)) }
        assertThrows(ImportFormatException::class.java) { ExportReader.parse("""{"hello":"world"}""") }
        assertThrows(ImportFormatException::class.java) { ExportReader.parse("name,age\nbob,3\n") }
        assertThrows(ImportFormatException::class.java) { ExportReader.parse("just some text") }
        assertThrows(ImportFormatException::class.java) { ExportReader.parse("") }
    }

    @Test
    fun `csv with two sessions is refused`() {
        val other = flock.copy(sessionId = "other-session", macAddress = "00:11:22:33:44:55")
        assertThrows(ImportFormatException::class.java) { ExportReader.parse(ExportWriter.csv(listOf(flock, other))) }
    }

    @Test
    fun `json session without ended_at is bounded by its devices`() {
        val open = session.copy(endedAt = null)
        val back = ExportReader.parse(ExportWriter.json(open, all, 0L))
        assertEquals(all.maxOf { it.lastSeen }, back.session.endedAt)
        assertNull(open.endedAt)
    }
}
