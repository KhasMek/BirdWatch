package com.khasmek.flockyou.detection

import com.khasmek.flockyou.wifi.WifiChannels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WifiApClassifierTest {

    private val all = PackId.entries.toSet()

    @Test
    fun `core flock and soundthinking OUIs match on wifi without any pack`() {
        val flock = DeviceClassifier.classifyWifiAp("70:C9:4E:7F:68:FF")!!
        assertEquals(DeviceType.FLOCK, flock.deviceType)
        assertEquals(DetectionMethod.WIFI_AP_OUI, flock.method)
        assertEquals(Confidence.HIGH, flock.confidence)
        assertNull(flock.pack)

        assertEquals(DeviceType.SOUNDTHINKING, DeviceClassifier.classifyWifiAp("d4:11:d6:00:00:01")!!.deviceType)
        assertEquals(Confidence.LOW, DeviceClassifier.classifyWifiAp("f4:6a:dd:00:00:01")!!.confidence) // contract mfr
    }

    @Test
    fun `wifi-only LE OUIs match on wifi when the pack is enabled, never on BLE`() {
        val wg = DeviceClassifier.classifyWifiAp("00:19:86:aa:bb:cc", all)!!
        assertEquals(DeviceType.WATCHGUARD, wg.deviceType)
        assertEquals(PackId.LAW_ENFORCEMENT, wg.pack)
        assertEquals(DeviceCategory.LAW_ENFORCEMENT, wg.deviceType.category)
        assertEquals(DeviceType.DIGITAL_ALLY, DeviceClassifier.classifyWifiAp("00:1B:63:00:00:00", all)!!.deviceType)
        assertEquals(DeviceType.UTILITY_INC, DeviceClassifier.classifyWifiAp("00:09:bc:00:00:00", all)!!.deviceType)

        // Same prefix on a BLE advertisement must not match: these vendors only appear as APs.
        assertNull(DeviceClassifier.classify(BleAdvertisement("00:19:86:aa:bb:cc"), all))

        // Pack off -> no match.
        assertNull(DeviceClassifier.classifyWifiAp("00:19:86:aa:bb:cc"))
        assertNull(DeviceClassifier.classifyWifiAp("00:19:86:aa:bb:cc", setOf(PackId.DRONES)))
    }

    @Test
    fun `axon OUI matches on both radios`() {
        assertEquals(DeviceType.AXON, DeviceClassifier.classifyWifiAp("00:25:DF:00:00:01", all)!!.deviceType)
        assertEquals(DeviceType.AXON, DeviceClassifier.classify(BleAdvertisement("00:25:DF:00:00:01"), all)!!.deviceType)
    }

    @Test
    fun `drone OUIs match on wifi with the drones pack`() {
        assertEquals(DeviceType.DJI, DeviceClassifier.classifyWifiAp("60:60:1F:12:34:56", setOf(PackId.DRONES))!!.deviceType)
        assertEquals(DeviceType.PARROT, DeviceClassifier.classifyWifiAp("90:3a:e6:12:34:56", setOf(PackId.DRONES))!!.deviceType)
        assertEquals(DeviceType.SKYDIO, DeviceClassifier.classifyWifiAp("38:1d:14:12:34:56", setOf(PackId.DRONES))!!.deviceType)
        assertEquals(DeviceCategory.DRONE, DeviceClassifier.classifyWifiAp("38:1d:14:12:34:56", all)!!.deviceType.category)
        assertNull(DeviceClassifier.classifyWifiAp("60:60:1F:12:34:56", setOf(PackId.LAW_ENFORCEMENT)))
        // Drone OUIs are WiFi-only; a BLE advert with one is ignored.
        assertNull(DeviceClassifier.classify(BleAdvertisement("60:60:1F:12:34:56"), all))
    }

    @Test
    fun `random home router does not match`() {
        assertNull(DeviceClassifier.classifyWifiAp("a4:2b:8c:11:22:33", all))
    }

    @Test
    fun `drones pack and LE pack need the wifi scan, wearables does not`() {
        assertEquals(true, SignaturePacks.DRONES.needsWifiScan)
        assertEquals(true, SignaturePacks.LAW_ENFORCEMENT.needsWifiScan)
        assertEquals(false, SignaturePacks.WEARABLE_CAMERAS.needsWifiScan)
    }

    @Test
    fun `frequency to channel`() {
        assertEquals(1, WifiChannels.fromFrequencyMhz(2412))
        assertEquals(6, WifiChannels.fromFrequencyMhz(2437))
        assertEquals(11, WifiChannels.fromFrequencyMhz(2462))
        assertEquals(14, WifiChannels.fromFrequencyMhz(2484))
        assertEquals(36, WifiChannels.fromFrequencyMhz(5180))
        assertEquals(149, WifiChannels.fromFrequencyMhz(5745))
        assertEquals(1, WifiChannels.fromFrequencyMhz(5955))
        assertNull(WifiChannels.fromFrequencyMhz(0))
    }
}
