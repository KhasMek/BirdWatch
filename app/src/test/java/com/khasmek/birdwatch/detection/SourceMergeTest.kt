package com.khasmek.birdwatch.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceMergeTest {

    private fun row(
        mac: String, source: DetectionSource, confidence: Confidence, tier: Int?, lastSeen: Long,
        sightings: Int = 1, name: String? = null, lat: Double? = null, rssi: Int = -60,
    ) = DetectedDevice(
        sessionId = "s", macAddress = mac, source = source, deviceName = name,
        detectionMethod = if (source == DetectionSource.ESP32_WIFI) DetectionMethod.WIFI_WILDCARD_PROBE_IE_SIG else DetectionMethod.WIFI_AP_OUI,
        deviceType = DeviceType.FLOCK, confidence = confidence, matchedOn = "82:6b:f2", tier = tier,
        rssi = rssi, latitude = lat, longitude = lat, accuracyMeters = lat?.let { 5f },
        firstSeen = lastSeen - 1_000, lastSeen = lastSeen, sightings = sightings,
    )

    @Test
    fun `esp32 tier-4 row keeps its identity over a low-confidence phone wifi row`() {
        val esp = row("82:6B:F2:00:00:01", DetectionSource.ESP32_WIFI, Confidence.HIGH, 4, lastSeen = 10_000, sightings = 3, rssi = -50)
        val wifi = row("82:6b:f2:00:00:01", DetectionSource.PHONE_WIFI, Confidence.LOW, null, lastSeen = 12_000, sightings = 2, name = "FlockAP", lat = 1.0, rssi = -70)
        val merged = SourceMerge.mergeAll(listOf(wifi, esp)).single()
        assertEquals(DetectionSource.ESP32_WIFI, merged.source)
        assertEquals(4, merged.tier)
        assertEquals(Confidence.HIGH, merged.confidence)
        assertEquals("FlockAP", merged.deviceName) // learned from the other radio
        assertEquals(-70, merged.rssi)            // most recent sighting
        assertEquals(1.0, merged.latitude!!, 0.0)
        assertEquals(5, merged.sightings)
        assertEquals(9_000, merged.firstSeen)
        assertEquals(12_000, merged.lastSeen)
    }

    @Test
    fun `equal evidence falls back to the most recent row`() {
        val a = row("AA:00:00:00:00:01", DetectionSource.BLE, Confidence.HIGH, null, lastSeen = 5_000)
        val b = row("AA:00:00:00:00:01", DetectionSource.PHONE_WIFI, Confidence.HIGH, null, lastSeen = 6_000)
        assertEquals(DetectionSource.PHONE_WIFI, SourceMerge.merge(a, b).source)
        assertEquals(DetectionSource.PHONE_WIFI, SourceMerge.merge(b, a).source)
    }

    @Test
    fun `distinct macs are untouched and ordered by recency`() {
        val a = row("AA:00:00:00:00:01", DetectionSource.BLE, Confidence.HIGH, null, lastSeen = 5_000)
        val b = row("BB:00:00:00:00:01", DetectionSource.BLE, Confidence.HIGH, null, lastSeen = 9_000)
        val out = SourceMerge.mergeAll(listOf(a, b))
        assertEquals(listOf(b, a), out)
        assertNull(out[0].deviceName)
    }
}
