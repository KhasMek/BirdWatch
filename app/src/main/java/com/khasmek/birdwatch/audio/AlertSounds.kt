package com.khasmek.birdwatch.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import com.khasmek.birdwatch.data.AppSettings
import com.khasmek.birdwatch.detection.Confidence
import com.khasmek.birdwatch.detection.DetectedDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Detection chirps through [SoundPool]. No audio assets: the tones are synthesised as tiny WAV
 * files in the cache directory on first use, mirroring the ESP32 firmware's piezo cadence:
 *
 *  - high-confidence hit: two-note ascending chirp, 2000 Hz -> 2800 Hz, 55 ms notes (firmware tier 4)
 *  - low-confidence hit:  single 1200 Hz blip, 45 ms (firmware tier 2)
 */
class AlertSounds(context: Context, private val settings: AppSettings) {

    private val appContext = context.applicationContext

    // ASSISTANCE_SONIFICATION rather than NOTIFICATION_EVENT: a detection chirp is feedback the user
    // asked for while driving, and must not be silenced by Do Not Disturb or a muted notification
    // stream the way a notification sound would be. It follows the media/system volume instead.
    private val soundPool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()

    @Volatile private var chirpId = 0
    @Volatile private var blipId = 0
    private val loaded = HashSet<Int>()

    init {
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            if (status == 0) synchronized(loaded) { loaded += sampleId }
            else Log.w(TAG, "sound $sampleId failed to load (status $status)")
        }
    }

    /** Synthesize + load the tones and subscribe to new detections. Call once from Application. */
    fun start(newDetections: Flow<DetectedDevice>, scope: CoroutineScope) {
        scope.launch {
            withContext(Dispatchers.IO) { loadTones() }
            newDetections.collect { playFor(it) }
        }
    }

    fun playFor(device: DetectedDevice) {
        if (!settings.audioAlerts.value) return
        val id = if (device.confidence == Confidence.HIGH) chirpId else blipId
        play(id)
    }

    /** Preview for the settings screen. False if the tones are not loaded yet. */
    fun playTest(): Boolean = play(chirpId)

    private fun play(id: Int): Boolean {
        if (id == 0 || synchronized(loaded) { id !in loaded }) return false
        return soundPool.play(id, 1f, 1f, 1, 0, 1f) != 0
    }

    private fun loadTones() {
        try {
            val chirp = File(appContext.cacheDir, "chirp_high.wav")
            val blip = File(appContext.cacheDir, "blip_low.wav")
            if (!chirp.exists()) writeWav(chirp, ToneSynth.chirp())
            if (!blip.exists()) writeWav(blip, ToneSynth.blip())
            chirpId = soundPool.load(chirp.path, 1)
            blipId = soundPool.load(blip.path, 1)
        } catch (e: Exception) {
            Log.e(TAG, "failed to prepare alert tones", e)
        }
    }

    private fun writeWav(file: File, pcm: ShortArray) {
        FileOutputStream(file).use { out -> out.write(ToneSynth.wav(pcm)) }
    }

    companion object {
        private const val TAG = "BirdWatch/Audio"
    }
}

/** 16-bit mono PCM tone synthesis + minimal WAV container. Pure Kotlin. */
object ToneSynth {
    const val SAMPLE_RATE = 44_100

    /** Two-note ascending chirp: 2000 Hz 55 ms, 25 ms gap, 2800 Hz 55 ms. */
    fun chirp(): ShortArray = tone(2000.0, 55) + silence(25) + tone(2800.0, 55)

    /** Single blip: 1200 Hz 45 ms. */
    fun blip(): ShortArray = tone(1200.0, 45)

    fun tone(hz: Double, ms: Int, amplitude: Double = 0.6): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val fade = min(n / 2, SAMPLE_RATE * 3 / 1000) // 3 ms fade in/out to avoid clicks
        return ShortArray(n) { i ->
            val env = when {
                i < fade -> i.toDouble() / fade
                i >= n - fade -> (n - i).toDouble() / fade
                else -> 1.0
            }
            (sin(2 * PI * hz * i / SAMPLE_RATE) * amplitude * env * Short.MAX_VALUE).toInt().toShort()
        }
    }

    fun silence(ms: Int): ShortArray = ShortArray(SAMPLE_RATE * ms / 1000)

    /** Wrap PCM samples in a canonical 44-byte RIFF/WAVE header. */
    fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val out = ByteArray(44 + dataBytes)
        fun str(off: Int, s: String) = s.forEachIndexed { i, c -> out[off + i] = c.code.toByte() }
        fun u32(off: Int, v: Int) { for (i in 0 until 4) out[off + i] = (v shr (8 * i)).toByte() }
        fun u16(off: Int, v: Int) { for (i in 0 until 2) out[off + i] = (v shr (8 * i)).toByte() }
        str(0, "RIFF"); u32(4, 36 + dataBytes); str(8, "WAVE")
        str(12, "fmt "); u32(16, 16); u16(20, 1); u16(22, 1)
        u32(24, SAMPLE_RATE); u32(28, SAMPLE_RATE * 2); u16(32, 2); u16(34, 16)
        str(36, "data"); u32(40, dataBytes)
        var o = 44
        for (s in pcm) { out[o] = s.toByte(); out[o + 1] = (s.toInt() shr 8).toByte(); o += 2 }
        return out
    }
}
