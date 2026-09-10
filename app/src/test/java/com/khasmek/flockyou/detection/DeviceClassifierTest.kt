package com.khasmek.flockyou.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceClassifierTest {

    private fun adv(
        mac: String = "00:11:22:33:44:55",
        name: String? = null,
        mfr: Set<Int> = emptySet(),
        uuids: List<String> = emptyList(),
    ) = BleAdvertisement(mac, name, mfr, uuids)

    // ---- Method 1: MAC OUI ------------------------------------------------

    @Test
    fun `flock OUI matches regardless of case`() {
        val c = DeviceClassifier.classify(adv(mac = "58:8E:81:FD:9B:CA"))!!
        assertEquals(DetectionMethod.MAC_PREFIX, c.method)
        assertEquals(DeviceType.FLOCK, c.deviceType)
        assertEquals(Confidence.HIGH, c.confidence)
        assertEquals("58:8e:81", c.matchedOn)
        assertNull(c.ravenFirmware)
    }

    @Test
    fun `every flock OUI in both lists is recognised`() {
        (DetectionSignatures.FLOCK_MAC_PREFIXES + DetectionSignatures.FLOCK_MAC_PREFIXES_2026).forEach { p ->
            val c = DeviceClassifier.classify(adv(mac = "$p:00:00:01"))
            assertEquals("prefix $p", DetectionMethod.MAC_PREFIX, c?.method)
        }
    }

    @Test
    fun `soundthinking OUI is its own method and type`() {
        val c = DeviceClassifier.classify(adv(mac = "d4:11:d6:aa:bb:cc"))!!
        assertEquals(DetectionMethod.MAC_PREFIX_SOUNDTHINKING, c.method)
        assertEquals(DeviceType.SOUNDTHINKING, c.deviceType)
        assertEquals(Confidence.HIGH, c.confidence)
    }

    @Test
    fun `contract manufacturer OUI is low confidence`() {
        DetectionSignatures.FLOCK_CONTRACT_MFR_MAC_PREFIXES.forEach { p ->
            val c = DeviceClassifier.classify(adv(mac = "$p:12:34:56"))!!
            assertEquals(DetectionMethod.MAC_PREFIX_MFR, c.method)
            assertEquals(DeviceType.FLOCK, c.deviceType)
            assertEquals(Confidence.LOW, c.confidence)
        }
    }

    @Test
    fun `signature lists do not overlap`() {
        val lists = listOf(
            DetectionSignatures.FLOCK_MAC_PREFIXES,
            DetectionSignatures.FLOCK_MAC_PREFIXES_2026,
            DetectionSignatures.FLOCK_CONTRACT_MFR_MAC_PREFIXES,
            DetectionSignatures.SOUNDTHINKING_MAC_PREFIXES,
        )
        val all = lists.flatten()
        assertEquals("duplicate prefixes across lists", all.size, all.toSet().size)
        all.forEach { assertEquals("bad prefix format: $it", 8, it.length) }
    }

    // ---- Method 2: device name ------------------------------------------

    @Test
    fun `device name substring match is case-insensitive`() {
        listOf("fs ext battery", "Penguin-3101300881", "FLOCK-7F68FF", "My PigVision Cam").forEach { n ->
            val c = DeviceClassifier.classify(adv(name = n))
            assertEquals("name $n", DetectionMethod.DEVICE_NAME, c?.method)
            assertEquals(DeviceType.FLOCK, c?.deviceType)
        }
    }

    @Test
    fun `unrelated name does not match`() {
        assertNull(DeviceClassifier.classify(adv(name = "Galaxy Buds")))
        assertNull(DeviceClassifier.classify(adv(name = "")))
        assertNull(DeviceClassifier.classify(adv(name = null)))
    }

    // ---- Method 3: manufacturer ID --------------------------------------

    @Test
    fun `manufacturer id 0x09C8 matches with no name`() {
        val c = DeviceClassifier.classify(adv(mfr = setOf(0x004C, 0x09C8)))!!
        assertEquals(DetectionMethod.BLE_MFR_ID, c.method)
        assertEquals("0x09C8", c.matchedOn)
    }

    @Test
    fun `other manufacturer ids do not match`() {
        assertNull(DeviceClassifier.classify(adv(mfr = setOf(0x004C, 0x0006))))
    }

    // ---- Method 4: Raven UUIDs ------------------------------------------

    @Test
    fun `every raven uuid triggers a raven detection`() {
        DetectionSignatures.RAVEN_SERVICE_UUIDS.forEach { u ->
            val c = DeviceClassifier.classify(adv(uuids = listOf(u)))
            assertEquals("uuid $u", DetectionMethod.RAVEN_UUID, c?.method)
            assertEquals(DeviceType.RAVEN, c?.deviceType)
        }
    }

    @Test
    fun `short 16-bit uuid forms are normalised`() {
        val c = DeviceClassifier.classify(adv(uuids = listOf("3100", "0x3200")))!!
        assertEquals(DetectionMethod.RAVEN_UUID, c.method)
        assertEquals("1.3.x", c.ravenFirmware)
    }

    @Test
    fun `generic SIG uuid alone is low confidence, custom raven service is high`() {
        val generic = DeviceClassifier.classify(adv(uuids = listOf(DetectionSignatures.RAVEN_DEVICE_INFO_SERVICE)))!!
        assertEquals(Confidence.LOW, generic.confidence)

        val custom = DeviceClassifier.classify(
            adv(uuids = listOf(DetectionSignatures.RAVEN_DEVICE_INFO_SERVICE, DetectionSignatures.RAVEN_NETWORK_SERVICE))
        )!!
        assertEquals(Confidence.HIGH, custom.confidence)
    }

    @Test
    fun `unrelated uuids do not match`() {
        assertNull(DeviceClassifier.classify(adv(uuids = listOf("0000180f-0000-1000-8000-00805f9b34fb", "fe9f"))))
    }

    // ---- Method 5: firmware estimation ----------------------------------

    @Test
    fun `firmware 1_1_x when legacy location without new gps`() {
        assertEquals(
            "1.1.x",
            DeviceClassifier.estimateRavenFirmware(
                listOf(DetectionSignatures.RAVEN_OLD_LOCATION_SERVICE, DetectionSignatures.RAVEN_OLD_HEALTH_SERVICE)
            )
        )
    }

    @Test
    fun `firmware 1_2_x when new gps without power`() {
        assertEquals(
            "1.2.x",
            DeviceClassifier.estimateRavenFirmware(
                listOf(DetectionSignatures.RAVEN_GPS_SERVICE, DetectionSignatures.RAVEN_NETWORK_SERVICE)
            )
        )
    }

    @Test
    fun `firmware 1_3_x when new gps with power`() {
        assertEquals(
            "1.3.x",
            DeviceClassifier.estimateRavenFirmware(
                listOf(DetectionSignatures.RAVEN_GPS_SERVICE, DetectionSignatures.RAVEN_POWER_SERVICE)
            )
        )
    }

    @Test
    fun `firmware unknown for other combinations`() {
        assertEquals("?", DeviceClassifier.estimateRavenFirmware(listOf(DetectionSignatures.RAVEN_ERROR_SERVICE)))
        assertEquals("?", DeviceClassifier.estimateRavenFirmware(emptyList()))
        // Power without GPS is not a defined pattern.
        assertEquals("?", DeviceClassifier.estimateRavenFirmware(listOf(DetectionSignatures.RAVEN_POWER_SERVICE)))
    }

    @Test
    fun `raven classification carries firmware estimate`() {
        val c = DeviceClassifier.classify(
            adv(uuids = listOf(DetectionSignatures.RAVEN_GPS_SERVICE, DetectionSignatures.RAVEN_POWER_SERVICE))
        )!!
        assertEquals("1.3.x", c.ravenFirmware)
    }

    // ---- Priority order --------------------------------------------------

    @Test
    fun `mac prefix wins over name, mfr id and uuid`() {
        val c = DeviceClassifier.classify(
            adv(
                mac = "ec:1b:bd:20:0c:72",
                name = "Penguin-1",
                mfr = setOf(0x09C8),
                uuids = listOf(DetectionSignatures.RAVEN_GPS_SERVICE)
            )
        )!!
        assertEquals(DetectionMethod.MAC_PREFIX, c.method)
        assertNull(c.ravenFirmware)
    }

    @Test
    fun `name wins over mfr id and uuid when mac is unknown`() {
        val c = DeviceClassifier.classify(
            adv(name = "Flock", mfr = setOf(0x09C8), uuids = listOf(DetectionSignatures.RAVEN_GPS_SERVICE))
        )!!
        assertEquals(DetectionMethod.DEVICE_NAME, c.method)
    }

    @Test
    fun `mfr id wins over uuid`() {
        val c = DeviceClassifier.classify(
            adv(mfr = setOf(0x09C8), uuids = listOf(DetectionSignatures.RAVEN_GPS_SERVICE))
        )!!
        assertEquals(DetectionMethod.BLE_MFR_ID, c.method)
    }

    @Test
    fun `nothing matches a plain device`() {
        assertNull(
            DeviceClassifier.classify(
                adv(mac = "a4:c1:38:12:34:56", name = "JBL Flip 6", mfr = setOf(0x0057), uuids = listOf("fe2c"))
            )
        )
    }
}
