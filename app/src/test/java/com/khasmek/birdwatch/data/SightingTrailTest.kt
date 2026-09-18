package com.khasmek.birdwatch.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SightingTrailTest {

    private fun s(lat: Double, lon: Double, rssi: Int, t: Long = 0) =
        SightingSample(sessionId = "s", macAddress = "AA", time = t, latitude = lat, longitude = lon, rssi = rssi)

    @Test
    fun `first sample is always kept, then time or distance must pass`() {
        assertTrue(SightingTrail.shouldSample(null, null, null, 0, 37.0, -122.0))
        // 2 s later, 3 m away: too soon and too close.
        assertFalse(SightingTrail.shouldSample(0, 37.0, -122.0, 2_000, 37.000027, -122.0))
        // 5 s later, same spot: time is enough.
        assertTrue(SightingTrail.shouldSample(0, 37.0, -122.0, 5_000, 37.0, -122.0))
        // 1 s later but 20 m away: distance is enough.
        assertTrue(SightingTrail.shouldSample(0, 37.0, -122.0, 1_000, 37.00018, -122.0))
    }

    @Test
    fun `distance approximation is close for short hops`() {
        // One degree of latitude is ~111 km; 0.001 deg ~ 111 m.
        assertEquals(111.2, SightingTrail.distanceMeters(37.0, -122.0, 37.001, -122.0), 1.0)
        // Longitude shrinks with latitude: 0.001 deg at 37 N ~ 88.8 m.
        assertEquals(88.8, SightingTrail.distanceMeters(37.0, -122.0, 37.0, -121.999), 1.0)
    }

    @Test
    fun `suggested position leans toward the strong readings`() {
        val samples = listOf(
            s(37.000, -122.000, rssi = -95), // faint, far edge of range
            s(37.001, -122.000, rssi = -95),
            s(37.0005, -122.0003, rssi = -45), // strong: right next to it
        )
        val (lat, lon) = SightingTrail.suggestedPosition(samples)!!
        assertEquals(37.0005, lat, 0.00003)
        assertEquals(-122.0003, lon, 0.00003)
    }

    @Test
    fun `too few samples give no suggestion`() {
        assertNull(SightingTrail.suggestedPosition(listOf(s(37.0, -122.0, -50), s(37.0, -122.0, -50))))
    }
}
