package com.khasmek.birdwatch.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneSynthTest {

    @Test
    fun `chirp is two notes with a gap`() {
        val ms = { n: Int -> ToneSynth.SAMPLE_RATE * n / 1000 }
        assertEquals(ms(55) + ms(25) + ms(55), ToneSynth.chirp().size)
    }

    @Test
    fun `tone starts and ends near silence and peaks near amplitude`() {
        val t = ToneSynth.tone(1000.0, 50, amplitude = 0.5)
        assertEquals(0, t.first().toInt())
        assertTrue(kotlin.math.abs(t.last().toInt()) < 200)
        val peak = t.maxOf { kotlin.math.abs(it.toInt()) }
        assertTrue("peak $peak", peak in (0.45 * Short.MAX_VALUE).toInt()..(0.5 * Short.MAX_VALUE).toInt())
    }

    @Test
    fun `wav header is canonical 44 bytes with correct sizes`() {
        val pcm = ToneSynth.blip()
        val wav = ToneSynth.wav(pcm)
        assertEquals(44 + pcm.size * 2, wav.size)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals("fmt ", String(wav, 12, 4))
        assertEquals("data", String(wav, 36, 4))
        fun u32(off: Int) = (0 until 4).sumOf { (wav[off + it].toInt() and 0xFF) shl (8 * it) }
        fun u16(off: Int) = (wav[off].toInt() and 0xFF) or ((wav[off + 1].toInt() and 0xFF) shl 8)
        assertEquals(36 + pcm.size * 2, u32(4))
        assertEquals(1, u16(20))                       // PCM
        assertEquals(1, u16(22))                       // mono
        assertEquals(ToneSynth.SAMPLE_RATE, u32(24))
        assertEquals(ToneSynth.SAMPLE_RATE * 2, u32(28)) // byte rate
        assertEquals(16, u16(34))                      // bits per sample
        assertEquals(pcm.size * 2, u32(40))
    }
}
