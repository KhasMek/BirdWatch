package com.khasmek.birdwatch.data

/**
 * Value validation for anything that arrives from outside the app: import / restore files and,
 * where it applies, the ESP32 serial line. Types are already enforced by the JSON / CSV readers;
 * this is about *values*: a MAC must look like a MAC, a coordinate must be on the planet, a
 * name must not be a megabyte, an id must be safe to put in a file name. Pure Kotlin.
 */
object ImportSanitizer {

    private val MAC = Regex("""^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$""")
    private val SESSION_ID = Regex("""^[A-Za-z0-9._-]{1,64}$""")

    const val MAX_NAME = 256
    const val MAX_ALIAS = 120
    const val MAX_NOTES = 4_000
    const val MAX_LABEL = 120
    const val MAX_ID_FIELD = 64
    const val MAX_SIGHTINGS = 10_000_000

    /**
     * The epoch .. 2200-01-01T00:00:00Z. Generous on purpose (a phone with a wrong clock still
     * writes a valid file); the point is to refuse the ±billion-year values ISO-8601 allows,
     * which overflow arithmetic and pin a session to the end of every list.
     */
    const val MIN_EPOCH_MS = 0L
    const val MAX_EPOCH_MS = 7_258_118_400_000L

    /** "aa:bb:cc:dd:ee:ff" in either case, colon-separated, nothing else. */
    fun isValidMac(s: String?): Boolean = s != null && MAC.matches(s.trim())

    /** Letters, digits, dot, dash, underscore; 1-64 chars. Safe as a primary key and in a file name. */
    fun isValidSessionId(s: String?): Boolean = s != null && SESSION_ID.matches(s)

    fun latitude(v: Double?): Double? = v?.takeIf { it.isFinite() && it in -90.0..90.0 }
    fun longitude(v: Double?): Double? = v?.takeIf { it.isFinite() && it in -180.0..180.0 }

    /** Both or neither: a lone latitude is meaningless. */
    fun coordinates(lat: Double?, lon: Double?): Pair<Double?, Double?> {
        val la = latitude(lat)
        val lo = longitude(lon)
        return if (la != null && lo != null) la to lo else null to null
    }

    fun altitudeM(v: Double?): Double? = v?.takeIf { it.isFinite() && it in -1_000.0..50_000.0 }
    fun accuracyM(v: Float?): Float? = v?.takeIf { it.isFinite() && it >= 0f && it <= 100_000f }
    fun rssi(v: Int?): Int = (v ?: 0).coerceIn(-127, 20)
    fun sightings(v: Int?): Int = (v ?: 1).coerceIn(1, MAX_SIGHTINGS)
    fun tier(v: Int?): Int? = v?.takeIf { it in 0..4 }
    fun channel(v: Int?): Int? = v?.takeIf { it in 1..233 }

    /**
     * Free text from a file: trimmed, control characters other than newline and tab dropped,
     * cut to [max] characters, blank -> null. Keeps the value useful, prevents a pathological
     * one from bloating the database or the UI.
     */
    fun text(s: String?, max: Int): String? {
        if (s == null) return null
        val cleaned = buildString(minOf(s.length, max)) {
            for (c in s) {
                if (length >= max) break
                if (c == '\n' || c == '\t' || c.code >= 0x20 && c.code != 0x7F) append(c)
            }
        }.trim()
        return cleaned.takeIf { it.isNotEmpty() }
    }

    /** Only characters safe in a file name; used for the session id fragment of export names. */
    fun fileNameFragment(s: String, max: Int = 8): String =
        s.filter { it.isLetterOrDigit() }.take(max).ifEmpty { "session" }
}
