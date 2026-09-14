package com.khasmek.birdwatch.detection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetectionTableTest {

    private fun dev(mac: String, t: Long) = DetectedDevice(
        sessionId = "s", macAddress = mac, source = DetectionSource.BLE, deviceName = null,
        detectionMethod = DetectionMethod.MAC_PREFIX, deviceType = DeviceType.FLOCK, confidence = Confidence.HIGH,
        matchedOn = "x", rssi = -60, firstSeen = t, lastSeen = t, sightings = 1,
    )

    @Test
    fun `mac case and whitespace differences merge into one row`() {
        val table = DetectionTable()
        val (first, isNew1) = table.upsert("f8:4d:00:00:00:01", create = { dev("f8:4d:00:00:00:01", 1) }, merge = { it })
        val (second, isNew2) = table.upsert(" F8:4D:00:00:00:01 ", create = { dev("x", 2) }, merge = { it.copy(sightings = it.sightings + 1, lastSeen = 2) })
        assertTrue(isNew1); assertFalse(isNew2)
        assertEquals(1, table.size)
        assertEquals(first.macAddress, second.macAddress)
        assertEquals(2, second.sightings)
    }

    @Test
    fun `devices flow is most recent first and clear empties it`() {
        val table = DetectionTable()
        table.upsert("AA:00:00:00:00:01", create = { dev("AA:00:00:00:00:01", 10) }, merge = { it })
        table.upsert("BB:00:00:00:00:01", create = { dev("BB:00:00:00:00:01", 20) }, merge = { it })
        table.upsert("AA:00:00:00:00:01", create = { dev("AA:00:00:00:00:01", 0) }, merge = { it.copy(lastSeen = 30) })
        assertEquals(listOf("AA:00:00:00:00:01", "BB:00:00:00:00:01"), table.devices.value.map { it.macAddress })
        table.clear()
        assertEquals(0, table.size)
        assertTrue(table.devices.value.isEmpty())
    }

    @Test
    fun `newDetections emits once per mac`() = runBlocking {
        val table = DetectionTable()
        val seen = mutableListOf<String>()
        // Unconfined: the collector subscribes right away and receives each emission synchronously.
        val job = launch(Dispatchers.Unconfined) { table.newDetections.collect { seen += it.macAddress } }
        repeat(3) { i -> table.upsert("AA:00:00:00:00:01", create = { dev("AA:00:00:00:00:01", i.toLong()) }, merge = { it }) }
        table.upsert("BB:00:00:00:00:01", create = { dev("BB:00:00:00:00:01", 9) }, merge = { it })
        job.cancel()
        assertEquals(listOf("AA:00:00:00:00:01", "BB:00:00:00:00:01"), seen)
    }
}
