package com.khasmek.birdwatch.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {

    @Test
    fun `mac addresses are redacted in any case or separator`() {
        assertEquals("DETECTED [mac] rssi=-52", Diagnostics.scrub("DETECTED 82:6B:F2:14:07:3A rssi=-52"))
        assertEquals("[mac]", Diagnostics.scrub("d4-11-d6-aa-bb-cc"))
    }

    @Test
    fun `coordinates are redacted but ordinary numbers are kept`() {
        val out = Diagnostics.scrub("fix 37.123456, -122.987654 acc 4.6 rssi -70 tier 4 v2026.09.1")
        assertEquals("fix [coord], [coord] acc 4.6 rssi -70 tier 4 v2026.09.1", out)
    }

    @Test
    fun `storage paths and content uris are redacted`() {
        val out = Diagnostics.scrub("Could not open content://com.android.providers.downloads.documents/document/1234 or /storage/emulated/0/Download/my_drive.json")
        assertEquals("Could not open [path] or [path]", out)
    }

    @Test
    fun `quoted names are redacted`() {
        val out = Diagnostics.scrub("""Row 3: device "Cam at Main & 3rd" has no mac_address""")
        assertEquals("""Row 3: device "[redacted]" has no mac_address""", out)
    }

    @Test
    fun `firmware detection lines and data-class toStrings are redacted`() {
        val banner = """[flockyou] DETECT-SSID type=wifi mac=82:6b:f2:14:07:3a ssid="Cam "quoted" name that is very long ${"x".repeat(100)}" rssi=-52 ch=6 count=17"""
        val out = Diagnostics.scrub(banner)
        assertFalse(out.contains("82:6b:f2")); assertFalse(out.contains("quoted")); assertFalse(out.contains("xxxx"))
        val ts = """IllegalStateException: bad row DetectedDevice(sessionId=s1, macAddress=D4:11:D6:AA:BB:CC, deviceName=Penguin-A1, alias=Home cam, notes=pole on NE corner, uasId=1581F123, rssi=-70)"""
        val out2 = Diagnostics.scrub(ts)
        for (secret in listOf("Penguin", "Home cam", "pole on", "1581F123", "D4:11")) assertFalse("$secret leaked in: $out2", out2.contains(secret))
        assertTrue(out2.contains("IllegalStateException")); assertTrue(out2.contains("rssi=-70"))
    }

    @Test
    fun `a typical stack trace keeps what matters and drops what identifies`() {
        val trace = """
            java.lang.IllegalStateException: Attempting to launch an unregistered ActivityResultLauncher with contract for content://media/1 and input "birdwatch_backup.json"
                at androidx.activity.result.ActivityResultRegistry.launch(ActivityResultRegistry.java:12)
                at com.khasmek.birdwatch.ui.screens.PreviousSessionScreenKt.foo(PreviousSessionScreen.kt:174)
        """.trimIndent()
        val out = Diagnostics.scrub(trace)
        assertTrue(out.contains("IllegalStateException"))
        assertTrue(out.contains("PreviousSessionScreen.kt:174"))
        assertFalse(out.contains("content://media"))
        assertFalse(out.contains("birdwatch_backup"))
    }
}
