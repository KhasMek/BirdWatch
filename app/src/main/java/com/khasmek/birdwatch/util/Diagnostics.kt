package com.khasmek.birdwatch.util

/**
 * Pure helpers for the "Copy diagnostics" report and the crash record. The report is meant to
 * be pasted into a public GitHub issue, so the rule is: versions, states and counts only. Never
 * a MAC, a coordinate, a device name, an alias, or a file path. [scrub] is the last line of
 * defence for text we do not fully control (exception messages, firmware banners).
 */
object Diagnostics {

    /** Something that looks like a MAC / BSSID / OUI-prefixed address. */
    private val MAC = Regex("""\b(?:[0-9A-Fa-f]{2}[:\-]){5}[0-9A-Fa-f]{2}\b""")

    /** A decimal with four or more fractional digits: a coordinate, in practice. */
    private val COORDINATE = Regex("""-?\d{1,3}\.\d{4,}""")

    /** Storage paths and content URIs, which carry file names the user chose. */
    private val PATH = Regex("""(?:content://|file://|/storage/|/sdcard/|/data/user/|/data/data/)\S*""")

    /** Anything that looks like an SSID or name in quotes after a colon, e.g. `name: "Cam 3"`. */
    private val QUOTED = Regex(""""[^"\n]{1,80}"""")

    /** Redact identifying fragments from free text before it goes anywhere near a report. */
    fun scrub(text: String): String = text
        .replace(MAC, "[mac]")
        .replace(PATH, "[path]")
        .replace(COORDINATE, "[coord]")
        .replace(QUOTED, "\"[redacted]\"")

    /** One `key: value` line. */
    fun line(key: String, value: Any?): String = "$key: ${value ?: "-"}"
}
