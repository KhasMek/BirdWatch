package com.khasmek.birdwatch.detection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SignaturePacksTest {

    private val all = PackId.entries.toSet()

    private fun adv(
        mac: String = "5A:11:22:33:44:55", // random (locally administered) address, no OUI meaning
        name: String? = null,
        mfr: Set<Int> = emptySet(),
        uuids: List<String> = emptyList(),
    ) = BleAdvertisement(mac, name, mfr, uuids)

    // ---- pack gating -----------------------------------------------------

    @Test
    fun `packs are ignored unless enabled`() {
        val axon = adv(mfr = setOf(0x034D))
        assertNull(DeviceClassifier.classify(axon))
        assertNull(DeviceClassifier.classify(axon, setOf(PackId.WEARABLE_CAMERAS)))
        assertEquals(DeviceType.AXON, DeviceClassifier.classify(axon, setOf(PackId.LAW_ENFORCEMENT))?.deviceType)
    }

    @Test
    fun `core heuristics win over packs and are unaffected by them`() {
        val flock = adv(mac = "58:8e:81:00:00:01", mfr = setOf(0x034D))
        val c = DeviceClassifier.classify(flock, all)!!
        assertEquals(DeviceType.FLOCK, c.deviceType)
        assertNull(c.pack)
    }

    // ---- Axon -------------------------------------------------------------

    @Test
    fun `axon matches by company id, service uuid, or oui`() {
        val byCid = DeviceClassifier.classify(adv(mfr = setOf(0x004C, 0x034D)), all)!!
        assertEquals(DetectionMethod.BLE_COMPANY_ID, byCid.method)
        assertEquals("0x034D", byCid.matchedOn)
        assertEquals(PackId.LAW_ENFORCEMENT, byCid.pack)
        assertEquals(DeviceCategory.LAW_ENFORCEMENT, byCid.deviceType.category)

        val byUuid = DeviceClassifier.classify(adv(uuids = listOf("fc81")), all)!!
        assertEquals(DetectionMethod.BLE_SERVICE_UUID, byUuid.method)
        assertEquals("0xFC81", byUuid.matchedOn)

        val byUuid128 = DeviceClassifier.classify(adv(uuids = listOf("0000fc81-0000-1000-8000-00805f9b34fb")), all)!!
        assertEquals(DeviceType.AXON, byUuid128.deviceType)

        listOf("00:25:DF", "00:1f:55", "00:0F:13").forEach { p ->
            val byOui = DeviceClassifier.classify(adv(mac = "$p:aa:bb:cc"), all)!!
            assertEquals("oui $p", DeviceType.AXON, byOui.deviceType)
            assertEquals(DetectionMethod.MAC_PREFIX, byOui.method)
        }
        assertTrue(DeviceClassifier.classify(adv(mfr = setOf(0x034D)), all)!!.confidence == Confidence.HIGH)
    }

    // ---- Meta glasses -----------------------------------------------------

    @Test
    fun `meta composite requires company id AND service uuid in the same advert`() {
        val both = DeviceClassifier.classify(adv(mfr = setOf(0x0D53), uuids = listOf("fd5f")), all)!!
        assertEquals(DeviceType.META_GLASSES, both.deviceType)
        assertEquals(DetectionMethod.BLE_COMPOSITE, both.method)
        assertEquals("0x0D53+0xFD5F", both.matchedOn)
        assertEquals(PackId.WEARABLE_CAMERAS, both.pack)

        // Either half alone is a false-positive magnet and must NOT match.
        assertNull(DeviceClassifier.classify(adv(mfr = setOf(0x0D53)), all))
        assertNull(DeviceClassifier.classify(adv(uuids = listOf("fd5f")), all))
    }

    @Test
    fun `meta glasses match by name`() {
        listOf("Ray-Ban Meta Wayfarer", "RAY-BAN 1234", "Oakley Meta HSTN", "wayfarer").forEach { n ->
            val c = DeviceClassifier.classify(adv(name = n), all)
            assertEquals("name $n", DeviceType.META_GLASSES, c?.deviceType)
            assertEquals(DetectionMethod.DEVICE_NAME, c?.method)
        }
        assertNull(DeviceClassifier.classify(adv(name = "Meta Quest 3"), all)) // headset, not glasses
    }

    // ---- registry hygiene --------------------------------------------------

    @Test
    fun `every signature cites at least one known source`() {
        SignaturePacks.OPTIONAL.forEach { pack ->
            pack.signatures.forEach { sig ->
                assertTrue("${pack.id} ${sig} has no sources", sig.sources.isNotEmpty())
                sig.sources.forEach { id -> assertTrue("unknown source $id", Sources.byId(id) != null) }
            }
        }
    }

    @Test
    fun `pack vendors belong to the pack's category and OUIs are well-formed`() {
        SignaturePacks.OPTIONAL.forEach { pack ->
            val categories = pack.signatures.map { it.vendor.category }.toSet()
            assertEquals("pack ${pack.id} mixes categories", 1, categories.size)
            pack.signatures.filterIsInstance<Signature.Oui>().forEach {
                assertEquals("bad prefix ${it.prefix}", 8, it.prefix.length)
                assertEquals(it.prefix, it.prefix.lowercase())
            }
        }
        assertTrue(SignaturePacks.OPTIONAL.all { it.beta })
    }

    @Test
    fun `no pack signature collides with core flock or raven identifiers`() {
        SignaturePacks.OPTIONAL.flatMap { it.signatures }.forEach { sig ->
            when (sig) {
                is Signature.Oui -> assertTrue(
                    sig.prefix !in DetectionSignatures.FLOCK_MAC_PREFIXES &&
                        sig.prefix !in DetectionSignatures.FLOCK_MAC_PREFIXES_2026 &&
                        sig.prefix !in DetectionSignatures.SOUNDTHINKING_MAC_PREFIXES
                )
                is Signature.CompanyId -> assertTrue(sig.id !in DetectionSignatures.BLE_MANUFACTURER_IDS)
                is Signature.ServiceUuid16 -> assertTrue(
                    DeviceClassifier.normalizeUuid(DeviceClassifier.hex16(sig.uuid)) !in DetectionSignatures.RAVEN_SERVICE_UUIDS
                )
                else -> Unit
            }
        }
    }
}
