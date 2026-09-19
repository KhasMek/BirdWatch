package com.khasmek.birdwatch.util

/**
 * Pure helpers for the "Copy diagnostics" report and the crash record. The report is meant to
 * be pasted into a public GitHub issue, so the rule is: versions, states and counts only. Never
 * a MAC, a coordinate, a device name, an alias, or a file path. [scrub] is the last line of
 * defence for text we do not fully control (exception messages); it is applied when a crash is
 * written AND again when the report is built, so tightening it here also cleans old records.
 */
object Diagnostics {

    /** Something that looks like a MAC / BSSID / OUI-prefixed address. */
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b""")

    /** A decimal with four or more fractional digits: a coordinate, in practice. */
    private val COORDINATE = Regex("""-?\d{1,3}\.\d{4,}""")

    /** Storage paths and content URIs, which carry file names the user chose. */
    private val PATH = Regex("""(?:content://|file://|/storage/|/sdcard/|/data/user(?:_de)?/|/data/data/|/mnt/)\S*""")

    /** Anything in double quotes on one line, whatever its length. */
    private val QUOTED = Regex(""""[^"\n]*"""")

    /**
     * Unquoted `field=value` pairs as Kotlin data-class `toString()` prints them. Every field
     * that can hold something a user typed or a radio heard is listed; the value runs to the
     * next comma, closing bracket or line end.
     */
    private val FIELD = Regex(
        """\b(deviceName|displayName|alias|notes|label|ssid|uasId|operatorId|matchedOn|name|latitude|longitude|macAddress|mac|oui)=[^,)\]\n]*""",
    )

    /**
     * Redact identifying fragments from free text before it goes anywhere near a report.
     * Fields first: their values run to the next `]`, and every later replacement inserts one.
     */
    fun scrub(text: String): String = text
        .replace(FIELD) { "${it.groupValues[1]}=[redacted]" }
        .replace(MAC, "[mac]")
        .replace(PATH, "[path]")
        .replace(COORDINATE, "[coord]")
        .replace(QUOTED, "\"[redacted]\"")

    /** One `key: value` line. */
    fun line(key: String, value: Any?): String = "$key: ${value ?: "-"}"
}
