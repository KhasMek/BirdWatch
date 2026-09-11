package com.khasmek.flockyou.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteIdClassifierTest {

    private fun ascii20(s: String) = s.toByteArray(Charsets.US_ASCII).copyOf(20)
    private val basicId = byteArrayOf(0x02, 0x12) + ascii20("SERIAL123") + ByteArray(3)
    private val odid = byteArrayOf(0x0D, 0x01) + basicId

    private val advRandomMac = "7A:00:11:22:33:44" // random address: no OUI meaning

    @Test
    fun `remote id over ble classifies as drone under the drones pack`() {
        val adv = BleAdvertisement(advRandomMac, serviceData = mapOf("fffa" to odid))
        val c = DeviceClassifier.classify(adv, setOf(PackId.DRONES))!!
        assertEquals(DetectionMethod.REMOTE_ID_BLE, c.method)
        assertEquals(DeviceType.REMOTE_ID_UAS, c.deviceType)
        assertEquals(DeviceCategory.DRONE, c.deviceType.category)
        assertEquals(Confidence.HIGH, c.confidence)
        assertEquals("SERIAL123", c.matchedOn)
        assertEquals(PackId.DRONES, c.pack)
        assertEquals("SERIAL123", c.remoteId!!.basicId!!.uasId)
    }

    @Test
    fun `128-bit uuid key form is accepted`() {
        val adv = BleAdvertisement(advRandomMac, serviceData = mapOf("0000fffa-0000-1000-8000-00805f9b34fb" to odid))
        assertEquals(DeviceType.REMOTE_ID_UAS, DeviceClassifier.classify(adv, setOf(PackId.DRONES))?.deviceType)
    }

    @Test
    fun `no match without the drones pack or with other service data`() {
        val adv = BleAdvertisement(advRandomMac, serviceData = mapOf("fffa" to odid))
        assertNull(DeviceClassifier.classify(adv))
        assertNull(DeviceClassifier.classify(adv, setOf(PackId.LAW_ENFORCEMENT, PackId.WEARABLE_CAMERAS)))
        val other = BleAdvertisement(advRandomMac, serviceData = mapOf("fe9f" to odid)) // Google service data
        assertNull(DeviceClassifier.classify(other, setOf(PackId.DRONES)))
    }

    @Test
    fun `core flock still wins over remote id`() {
        val adv = BleAdvertisement("58:8e:81:00:00:01", serviceData = mapOf("fffa" to odid))
        assertEquals(DeviceType.FLOCK, DeviceClassifier.classify(adv, PackId.entries.toSet())!!.deviceType)
    }

    @Test
    fun `wifi beacon remote id classifies under the drones pack only`() {
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 0x00) +
            byteArrayOf(0xF2.toByte(), 25, 1) + basicId
        val c = DeviceClassifier.classifyWifiRemoteId(ie, setOf(PackId.DRONES))!!
        assertEquals(DetectionMethod.REMOTE_ID_WIFI, c.method)
        assertEquals("SERIAL123", c.matchedOn)
        assertNull(DeviceClassifier.classifyWifiRemoteId(ie, emptySet()))
        assertNull(DeviceClassifier.classifyWifiRemoteId(byteArrayOf(0, 0x50, 0xF2.toByte(), 1), setOf(PackId.DRONES)))
    }

    @Test
    fun `withRemoteId fills columns and keeps earlier values`() {
        val loc = byteArrayOf(0x12, 0x20, 0, 0, 0) +
            le32(377_749_000) + le32(-1_224_194_000) + le16(0) + le16(2440) + le16(0) + byteArrayOf(0, 0) + le16(0xFFFF) + byteArrayOf(0, 0)
        val first = RemoteId.parseBleServiceData(byteArrayOf(0x0D, 0) + basicId)!!
        val second = RemoteId.parseBleServiceData(byteArrayOf(0x0D, 1) + loc)!!

        val base = DetectedDevice(
            sessionId = "s", macAddress = advRandomMac, source = DetectionSource.BLE, deviceName = null,
            detectionMethod = DetectionMethod.REMOTE_ID_BLE, deviceType = DeviceType.REMOTE_ID_UAS,
            confidence = Confidence.HIGH, matchedOn = "SERIAL123", rssi = -60, firstSeen = 1, lastSeen = 1,
        )
        val a = base.withRemoteId(first)
        assertEquals("SERIAL123", a.uasId)
        assertEquals("SERIAL123", a.displayName)
        assertNull(a.targetLatitude)

        val b = a.withRemoteId(second) // location-only broadcast must not erase the serial
        assertEquals("SERIAL123", b.uasId)
        assertEquals(37.7749, b.targetLatitude!!, 1e-6)
        assertEquals(220.0, b.targetAltitudeM!!, 1e-9)
        assertEquals(true, b.hasTargetLocation)
    }

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
}
