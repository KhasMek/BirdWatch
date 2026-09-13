package com.khasmek.birdwatch.usb

import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectionMethod
import com.khasmek.birdwatch.detection.DetectionSource
import com.khasmek.birdwatch.detection.DeviceType
import com.khasmek.birdwatch.usb.FirmwareLineParser.toDetectedDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionImportTest {

    @Test
    fun `stored method names map to detection methods`() {
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, FirmwareLineParser.methodFromStoredName("wildcard_probe_ie_sig"))
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE, FirmwareLineParser.methodFromStoredName("wildcard_probe"))
        assertEquals(DetectionMethod.WIFI_OUI_ADDR2, FirmwareLineParser.methodFromStoredName("oui_addr2"))
        assertEquals(DetectionMethod.WIFI_OUI_ADDR1, FirmwareLineParser.methodFromStoredName("oui_addr1"))
        assertEquals(DetectionMethod.WIFI_OUI_ADDR3, FirmwareLineParser.methodFromStoredName("oui_addr3"))
        assertEquals(DetectionMethod.WIFI_OUI_ADDR1, FirmwareLineParser.methodFromStoredName("oui_addr1_addr3"))
        assertEquals(DetectionMethod.WIFI_SSID, FirmwareLineParser.methodFromStoredName("ssid"))
        // Live-line spelling with the prefix is accepted too.
        assertEquals(DetectionMethod.WIFI_OUI_ADDR2, FirmwareLineParser.methodFromStoredName("wifi_oui_addr2"))
        assertEquals(DetectionMethod.UNKNOWN, FirmwareLineParser.methodFromStoredName("unknown"))
        assertEquals(DetectionMethod.UNKNOWN, FirmwareLineParser.methodFromStoredName(null))
    }

    @Test
    fun `dumped record becomes an imported device anchored at import time`() {
        val rec = FirmwareLineParser.parse(
            """{"event":"session_det","mac":"d8:f3:bc:7d:cb:1d","method":"wildcard_probe_ie_sig","tier":4,"rssi":-61,"channel":11,"first":100000,"last":160000,"count":7,"ssid":""}"""
        ) as FirmwareMessage.SessionRecord
        val d = rec.toDetectedDevice("imp", importedAt = 1_000_000L)
        assertEquals("imp", d.sessionId)
        assertEquals("D8:F3:BC:7D:CB:1D", d.macAddress)
        assertEquals(DetectionSource.ESP32_WIFI, d.source)
        assertEquals(DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG, d.detectionMethod)
        assertEquals(DeviceType.FLOCK, d.deviceType)
        assertEquals(Confidence.HIGH, d.confidence)
        assertEquals("d8:f3:bc", d.matchedOn)
        assertEquals(4, d.tier)
        assertEquals(11, d.channel)
        assertEquals(-61, d.rssi)
        assertEquals(7, d.sightings)
        assertEquals(1_000_000L, d.lastSeen)
        assertEquals(1_000_000L - 60_000L, d.firstSeen) // 60 s span preserved
        assertNull(d.latitude)
        assertNull(d.deviceName)
    }

    @Test
    fun `low tier record is low confidence and keeps ssid as name`() {
        val rec = FirmwareLineParser.parse(
            """{"event":"session_det","mac":"70:c9:4e:00:00:01","method":"oui_addr1_addr3","tier":1,"rssi":-80,"channel":1,"first":5,"last":5,"count":0,"ssid":"Flock-7F68FF"}"""
        ) as FirmwareMessage.SessionRecord
        val d = rec.toDetectedDevice("imp", importedAt = 50L)
        assertEquals(Confidence.LOW, d.confidence)
        assertEquals(DetectionMethod.WIFI_OUI_ADDR1, d.detectionMethod)
        assertEquals("Flock-7F68FF", d.deviceName)
        assertEquals(1, d.sightings) // count 0 clamps to 1
        assertEquals(50L, d.firstSeen)
    }
}
