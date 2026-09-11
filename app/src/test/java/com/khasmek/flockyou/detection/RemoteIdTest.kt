package com.khasmek.flockyou.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test vectors are built by hand from the ASTM F3411 byte layout (not from an encoder), so a
 * misread offset in the parser shows up as a wrong field rather than round-tripping silently.
 */
class RemoteIdTest {

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun ascii20(s: String) = s.toByteArray(Charsets.US_ASCII).copyOf(20)

    /** Basic ID: serial number (1), multirotor (2), "1581F4Q9TESTSERIAL". */
    private val basicId: ByteArray = byteArrayOf(0x02 /* type 0, version 2 */, 0x12) + ascii20("1581F4Q9TESTSERIAL") + ByteArray(3)

    /**
     * Location: airborne (2), height AGL, E/W segment set (track +180), speed multiplier 0.
     * track raw 30 -> 210 deg; speed raw 40 -> 10 m/s; vspeed -4 -> -2 m/s; lat 37.7749; lng -122.4194;
     * pressure alt raw 2400 -> 200 m; geodetic raw 2440 -> 220 m; height raw 2200 -> 100 m;
     * timestamp 12345 tenths.
     */
    private val location: ByteArray = byteArrayOf(
        0x12,                       // type 1, version 2
        0x26,                       // status 2 <<4 | AGL(0x04) | EW(0x02) | mult 0
        30, 40, (-4).toByte(),
    ) + le32(377_749_000) + le32(-1_224_194_000) + le16(2400) + le16(2440) + le16(2200) +
        byteArrayOf(0x00, 0x00) + le16(12345) + byteArrayOf(0x00, 0x00)

    /** System: operator at 37.78, -122.42, live GNSS (1), area count 1, radius 250 m (raw 25), ts = 1000 s after 2019 epoch. */
    private val system: ByteArray = byteArrayOf(0x42 /* type 4 */, 0x01) + le32(377_800_000) + le32(-1_224_200_000) +
        le16(1) + byteArrayOf(25) + le16(0) + le16(0) + byteArrayOf(0x00) + le16(2100) + le32(1000) + byteArrayOf(0x00)

    private val operatorId: ByteArray = byteArrayOf(0x52 /* type 5 */, 0x00) + ascii20("FIN87astrdge12k8") + ByteArray(3)

    private fun pack(vararg msgs: ByteArray): ByteArray =
        byteArrayOf(0xF2.toByte(), 25, msgs.size.toByte()) + msgs.fold(ByteArray(0)) { acc, m -> acc + m }

    private fun bleServiceData(body: ByteArray, counter: Int = 7) = byteArrayOf(0x0D, counter.toByte()) + body

    @Test
    fun `single basic id over BLE`() {
        val p = RemoteId.parseBleServiceData(bleServiceData(basicId))!!
        assertEquals(7, p.counter)
        val b = p.basicId!!
        assertEquals(1, b.idType)
        assertEquals("Serial number", b.idTypeLabel)
        assertEquals(2, b.uaType)
        assertEquals("Helicopter / multirotor", b.uaTypeLabel)
        assertEquals("1581F4Q9TESTSERIAL", b.uasId)
        assertEquals("1581F4Q9TESTSERIAL", p.label)
    }

    @Test
    fun `location fields decode with spec scaling`() {
        val l = RemoteId.parseBleServiceData(bleServiceData(location))!!.location!!
        assertEquals(2, l.status)
        assertEquals("Airborne", l.statusLabel)
        assertTrue(l.heightAgl)
        assertEquals(210, l.trackDeg)
        assertEquals(10.0, l.speedHorizontalMps!!, 1e-9)
        assertEquals(-2.0, l.speedVerticalMps!!, 1e-9)
        assertEquals(37.7749, l.latitude!!, 1e-6)
        assertEquals(-122.4194, l.longitude!!, 1e-6)
        assertEquals(200.0, l.altitudePressureM!!, 1e-9)
        assertEquals(220.0, l.altitudeGeodeticM!!, 1e-9)
        assertEquals(100.0, l.heightM!!, 1e-9)
        assertEquals(12345, l.timestampTenths)
    }

    @Test
    fun `unknown sentinels decode to null`() {
        val unknown = byteArrayOf(0x12, 0x01 /* mult 1 */, 200.toByte() /* track >179 */, 255.toByte(), 63) +
            le32(0) + le32(0) + le16(0) + le16(0) + le16(0) + byteArrayOf(0, 0) + le16(0xFFFF) + byteArrayOf(0, 0)
        val l = RemoteId.parseBleServiceData(bleServiceData(unknown))!!.location!!
        assertNull(l.trackDeg); assertNull(l.speedHorizontalMps); assertNull(l.speedVerticalMps)
        assertNull(l.latitude); assertNull(l.longitude); assertNull(l.altitudeGeodeticM); assertNull(l.timestampTenths)
    }

    @Test
    fun `high speed uses the second multiplier`() {
        val fast = byteArrayOf(0x12, 0x21 /* airborne, mult 1 */, 0, 100, 0) + ByteArray(20)
        val l = RemoteId.parseBleServiceData(bleServiceData(fast))!!.location!!
        assertEquals(100 * 0.75 + 255 * 0.25, l.speedHorizontalMps!!, 1e-9)
    }

    @Test
    fun `message pack yields every message and system operator position`() {
        val p = RemoteId.parseBleServiceData(bleServiceData(pack(basicId, location, system, operatorId)))!!
        assertEquals(4, p.messages.size)
        val s = p.system!!
        assertEquals(37.78, s.operatorLatitude!!, 1e-6)
        assertEquals(-122.42, s.operatorLongitude!!, 1e-6)
        assertEquals("Live GNSS", s.operatorLocationLabel)
        assertEquals(1, s.areaCount)
        assertEquals(250, s.areaRadiusM)
        assertEquals(50.0, s.operatorAltitudeM!!, 1e-9)
        assertEquals((1000L + RemoteId.SYSTEM_EPOCH_OFFSET_S) * 1000, s.timestampEpochMs)
        assertEquals("FIN87astrdge12k8", p.operatorId!!.operatorId)
    }

    @Test
    fun `wifi vendor ie with ASD-STAN oui decodes the same pack`() {
        val ie = byteArrayOf(0xFA.toByte(), 0x0B, 0xBC.toByte(), 0x0D, 3) + pack(basicId, location)
        assertTrue(RemoteId.isWifiRemoteIdIe(ie))
        val p = RemoteId.parseWifiVendorIe(ie)!!
        assertEquals(3, p.counter)
        assertEquals("1581F4Q9TESTSERIAL", p.basicId!!.uasId)
        assertEquals(37.7749, p.location!!.latitude!!, 1e-6)
    }

    @Test
    fun `rejects non-odid data`() {
        assertNull(RemoteId.parseBleServiceData(null))
        assertNull(RemoteId.parseBleServiceData(byteArrayOf(0x0D)))
        assertNull(RemoteId.parseBleServiceData(byteArrayOf(0x01, 0x00) + basicId)) // wrong app code
        assertNull(RemoteId.parseWifiVendorIe(byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x0D, 0) + pack(basicId))) // Microsoft OUI
        assertNull(RemoteId.parseBleServiceData(bleServiceData(byteArrayOf(0xF2.toByte(), 24, 1) + basicId))) // bad pack size
    }

    @Test
    fun `unknown message types are preserved not dropped`() {
        val weird = byteArrayOf(0x92.toByte()) + ByteArray(24)
        val p = RemoteId.parseBleServiceData(bleServiceData(weird))!!
        assertEquals(RemoteId.Message.Unknown(9), p.messages.single())
        assertEquals("Remote ID", p.label)
    }
}
