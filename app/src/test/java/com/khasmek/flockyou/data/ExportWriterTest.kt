package com.khasmek.flockyou.data

import com.khasmek.flockyou.detection.Confidence
import com.khasmek.flockyou.detection.DetectedDevice
import com.khasmek.flockyou.detection.DetectionMethod
import com.khasmek.flockyou.detection.DetectionSource
import com.khasmek.flockyou.detection.DeviceType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportWriterTest {

    private val session = ScanSession(id = "abcdef12-0000-0000-0000-000000000000", startedAt = 1_789_071_969_381L, endedAt = 1_789_071_997_367L)

    private val flock = DetectedDevice(
        sessionId = session.id, macAddress = "82:6B:F2:14:07:3A", source = DetectionSource.ESP32_WIFI,
        deviceName = null, detectionMethod = DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, deviceType = DeviceType.FLOCK,
        confidence = Confidence.HIGH, matchedOn = "82:6b:f2", tier = 4, channel = 6, rssi = -52,
        latitude = 37.123456789, longitude = -122.987654321, accuracyMeters = 4.6f,
        firstSeen = 1_789_071_970_000L, lastSeen = 1_789_071_990_000L, sightings = 12,
    )

    private val raven = DetectedDevice(
        sessionId = session.id, macAddress = "D4:11:D6:AA:BB:CC", source = DetectionSource.BLE,
        deviceName = "Raven, \"unit 7\"", detectionMethod = DetectionMethod.RAVEN_UUID, deviceType = DeviceType.RAVEN,
        confidence = Confidence.HIGH, matchedOn = "00003100-0000-1000-8000-00805f9b34fb", ravenFirmware = "1.3.x",
        rssi = -70, firstSeen = 1_789_071_971_000L, lastSeen = 1_789_071_972_000L, sightings = 1,
    )

    @Test
    fun `json has session and every device field`() {
        val root = Json.parseToJsonElement(ExportWriter.json(session, listOf(flock, raven), 1_789_072_000_000L)).jsonObject
        assertEquals("2026-09-10T20:26:09.381Z", root["session"]!!.jsonObject["started_at"]!!.jsonPrimitive.content)
        val devices = root["devices"]!!.jsonArray
        assertEquals(2, devices.size)
        val d0 = devices[0].jsonObject
        assertEquals("82:6B:F2:14:07:3A", d0["mac_address"]!!.jsonPrimitive.content)
        assertEquals("wifi_wildcard_probe_ie_sig", d0["detection_method"]!!.jsonPrimitive.content)
        assertEquals("4", d0["detection_tier"]!!.jsonPrimitive.content)
        assertEquals("ESP32_WIFI", d0["source"]!!.jsonPrimitive.content)
        assertEquals("FLOCK_ALPR", d0["category"]!!.jsonPrimitive.content)
        assertEquals("null", d0["device_name"].toString())
        val d1 = devices[1].jsonObject
        assertEquals("1.3.x", d1["raven_fw"]!!.jsonPrimitive.content)
        assertEquals("null", d1["latitude"].toString())
    }

    @Test
    fun `csv has header and escapes commas and quotes`() {
        val lines = ExportWriter.csv(listOf(flock, raven)).trimEnd().lines()
        assertEquals(3, lines.size)
        assertEquals(ExportWriter.CSV_HEADER.joinToString(","), lines[0])
        assertTrue(lines[1].contains(",37.123457,-122.987654,4.6,"))
        assertTrue(lines[1].contains(",wifi_wildcard_probe_ie_sig,FLOCK,FLOCK_ALPR,HIGH,82:6b:f2,,4,6,-52,"))
        // Name with a comma and quotes is quoted with doubled quotes.
        assertTrue(lines[2].contains("\"Raven, \"\"unit 7\"\"\""))
        assertEquals(ExportWriter.CSV_HEADER.size, splitCsv(lines[2]).size)
    }

    @Test
    fun `kml only includes located devices and uses lng,lat order`() {
        val kml = ExportWriter.kml(session, listOf(flock, raven))
        assertTrue(kml.contains("<coordinates>-122.987654,37.123457,0</coordinates>"))
        assertEquals(1, Regex("<Placemark>").findAll(kml).count())
        assertTrue(kml.contains("<styleUrl>#flock</styleUrl>"))
        assertTrue(kml.contains("2 devices, 1 with GPS"))
        assertFalse(kml.contains("D4:11:D6")) // raven has no GPS
    }

    @Test
    fun `kml escapes xml in names`() {
        val nasty = flock.copy(deviceName = "<Flock & \"Co\">")
        val kml = ExportWriter.kml(session, listOf(nasty))
        assertTrue(kml.contains("<name>Flock: &lt;Flock &amp; &quot;Co&quot;&gt;</name>"))
    }

    @Test
    fun `file name is sortable and unique per session`() {
        assertEquals("flockyou_20260910T202609Z_abcdef12.kml", ExportWriter.fileName(ExportFormat.KML, session))
    }

    @Test
    fun `csv cell escaping rules`() {
        assertEquals("plain", ExportWriter.csvCell("plain"))
        assertEquals("\"a,b\"", ExportWriter.csvCell("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", ExportWriter.csvCell("say \"hi\""))
        assertEquals("\"line\nbreak\"", ExportWriter.csvCell("line\nbreak"))
    }

    private fun splitCsv(line: String): List<String> {
        val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                q && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> q = !q
                c == ',' && !q -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
