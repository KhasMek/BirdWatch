package com.khasmek.flockyou.usb

import com.khasmek.flockyou.detection.Confidence
import com.khasmek.flockyou.detection.DetectionMethod
import com.khasmek.flockyou.detection.DetectionSource
import com.khasmek.flockyou.detection.DeviceType
import com.khasmek.flockyou.location.GeoFix
import com.khasmek.flockyou.usb.FirmwareLineParser.mergeInto
import com.khasmek.flockyou.usb.FirmwareLineParser.toDetectedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareLineParserTest {

    // Verbatim from ../flock-you/README.md "Flask dashboard integration".
    private val detectionLine =
        """{"event":"detection","detection_method":"wifi_wildcard_probe_ie_sig","detection_tier":4,""" +
            """"protocol":"wifi_2_4ghz","mac_address":"82:6b:f2:14:07:3a","oui":"82:6b:f2","device_name":"",""" +
            """"rssi":-52,"channel":6,"frequency":2437,"ssid":""}"""

    private val configLine =
        """{"event":"config","beep_mask":31,"oui_count":32,"tiers":[{"tier":0,"method":"wifi_ssid","beep":1},""" +
            """{"tier":1,"method":"wifi_oui_addr1_addr3","beep":1},{"tier":2,"method":"wifi_oui_addr2","beep":1},""" +
            """{"tier":3,"method":"wifi_wildcard_probe","beep":1},{"tier":4,"method":"wifi_wildcard_probe_ie_sig","beep":0}]}"""

    @Test
    fun `parses a live detection line`() {
        val m = FirmwareLineParser.parse(detectionLine) as FirmwareMessage.Detection
        assertEquals("wifi_wildcard_probe_ie_sig", m.method)
        assertEquals(4, m.tier)
        assertEquals("82:6B:F2:14:07:3A", m.macAddress)
        assertEquals("82:6b:f2", m.oui)
        assertNull(m.deviceName)
        assertEquals(-52, m.rssi)
        assertEquals(6, m.channel)
        assertEquals(2437, m.frequencyMhz)
        assertNull(m.ssid)
        assertEquals("wifi_2_4ghz", m.protocol)
    }

    @Test
    fun `detection is recognised by detection_method even without event`() {
        val m = FirmwareLineParser.parse("""{"detection_method":"wifi_oui_addr2","mac_address":"70:c9:4e:00:00:01","rssi":-70}""")
        assertTrue(m is FirmwareMessage.Detection)
        m as FirmwareMessage.Detection
        assertEquals(2, m.tier) // inferred from method when detection_tier is absent
    }

    @Test
    fun `parses config line with tiers`() {
        val m = FirmwareLineParser.parse(configLine) as FirmwareMessage.Config
        assertEquals(31, m.beepMask)
        assertEquals(32, m.ouiCount)
        assertEquals(5, m.tiers.size)
        assertEquals(FirmwareMessage.TierConfig(4, "wifi_wildcard_probe_ie_sig", beep = false), m.tiers[4])
    }

    @Test
    fun `parses session dump lines`() {
        assertEquals(
            FirmwareMessage.SessionBegin("prev", 3),
            FirmwareLineParser.parse("""{"event":"session_begin","source":"prev","count":3}""")
        )
        val rec = FirmwareLineParser.parse(
            """{"event":"session_det","mac":"d8:f3:bc:7d:cb:1d","method":"wifi_oui_addr2","tier":2,"rssi":-61,"channel":11,"first":12345,"last":99999,"count":7,"ssid":""}"""
        ) as FirmwareMessage.SessionRecord
        assertEquals("D8:F3:BC:7D:CB:1D", rec.macAddress)
        assertEquals(2, rec.tier)
        assertEquals(7, rec.count)
        assertEquals(12345L, rec.firstSeenUptimeMs)
        assertNull(rec.ssid)
        assertEquals(
            FirmwareMessage.SessionEnd("live", 0),
            FirmwareLineParser.parse("""{"event":"session_end","source":"live","count":0}""")
        )
        assertEquals(
            FirmwareMessage.SessionError("prev", "no valid session file"),
            FirmwareLineParser.parse("""{"event":"session_error","source":"prev","error":"no valid session file"}""")
        )
    }

    @Test
    fun `banner lines become Text and blanks are dropped`() {
        assertEquals(
            FirmwareMessage.Text("[flockyou] scanning (ch=6 mode=custom det=0)"),
            FirmwareLineParser.parse("[flockyou] scanning (ch=6 mode=custom det=0)\r")
        )
        assertNull(FirmwareLineParser.parse(""))
        assertNull(FirmwareLineParser.parse("   \r\n"))
    }

    @Test
    fun `malformed json never throws`() {
        assertTrue(FirmwareLineParser.parse("""{"event":"detection","mac_address""") is FirmwareMessage.Text)
        assertTrue(FirmwareLineParser.parse("""{"event":"detection"}""") is FirmwareMessage.Unknown)
        assertTrue(FirmwareLineParser.parse("""{"event":"something_new","x":1}""") is FirmwareMessage.Unknown)
        assertTrue(FirmwareLineParser.parse("""[1,2,3]""") is FirmwareMessage.Text)
    }

    @Test
    fun `escaped ssid is unescaped`() {
        val m = FirmwareLineParser.parse(
            """{"event":"detection","detection_method":"wifi_ssid","detection_tier":0,"mac_address":"aa:bb:cc:dd:ee:ff","rssi":-40,"ssid":"Flock-\"Cam\""}"""
        ) as FirmwareMessage.Detection
        assertEquals("""Flock-"Cam"""", m.ssid)
    }

    // ---- mapping into DetectedDevice --------------------------------

    @Test
    fun `detection maps to a high-confidence Flock row with tier and channel`() {
        val d = FirmwareLineParser.parse(detectionLine) as FirmwareMessage.Detection
        val fix = GeoFix(37.0, -122.0, 5f, 1000L)
        val row = d.toDetectedDevice("sess", fix, 2000L)
        assertEquals("sess", row.sessionId)
        assertEquals(DetectionSource.ESP32_WIFI, row.source)
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, row.detectionMethod)
        assertEquals(DeviceType.FLOCK, row.deviceType)
        assertEquals(Confidence.HIGH, row.confidence)
        assertEquals("82:6b:f2", row.matchedOn)
        assertEquals(4, row.tier)
        assertEquals(6, row.channel)
        assertEquals(37.0, row.latitude!!, 0.0)
        assertEquals(5f, row.accuracyMeters!!, 0f)
        assertEquals(2000L, row.firstSeen)
        assertNull(row.ravenFirmware)
    }

    @Test
    fun `tiers 0 to 2 are low confidence`() {
        listOf(0, 1, 2).forEach { assertEquals("tier $it", Confidence.LOW, FirmwareLineParser.confidenceForTier(it)) }
        listOf(3, 4).forEach { assertEquals("tier $it", Confidence.HIGH, FirmwareLineParser.confidenceForTier(it)) }
    }

    @Test
    fun `merge upgrades tier but never downgrades`() {
        val low = FirmwareLineParser.parse(
            """{"event":"detection","detection_method":"wifi_oui_addr1","detection_tier":1,"mac_address":"82:6b:f2:14:07:3a","rssi":-80,"channel":1}"""
        ) as FirmwareMessage.Detection
        val high = FirmwareLineParser.parse(detectionLine) as FirmwareMessage.Detection

        val first = low.toDetectedDevice("s", null, 1L)
        assertEquals(1, first.tier)
        assertEquals(Confidence.LOW, first.confidence)

        val upgraded = high.mergeInto(first, null, 2L)
        assertEquals(4, upgraded.tier)
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, upgraded.detectionMethod)
        assertEquals(Confidence.HIGH, upgraded.confidence)
        assertEquals(2, upgraded.sightings)
        assertEquals(-52, upgraded.rssi)
        assertEquals(6, upgraded.channel)

        val notDowngraded = low.mergeInto(upgraded, null, 3L)
        assertEquals(4, notDowngraded.tier)
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, notDowngraded.detectionMethod)
        assertEquals(Confidence.HIGH, notDowngraded.confidence)
        assertEquals(3, notDowngraded.sightings)
        assertEquals(-80, notDowngraded.rssi) // RSSI always reflects the latest frame
        assertEquals(1L, notDowngraded.firstSeen)
        assertEquals(3L, notDowngraded.lastSeen)
    }

    @Test
    fun `merge keeps existing GPS when no fix is available`() {
        val d = FirmwareLineParser.parse(detectionLine) as FirmwareMessage.Detection
        val withFix = d.toDetectedDevice("s", GeoFix(1.0, 2.0, 3f, 0L), 1L)
        val merged = d.mergeInto(withFix, null, 2L)
        assertEquals(1.0, merged.latitude!!, 0.0)
        assertEquals(3f, merged.accuracyMeters!!, 0f)
    }
}
