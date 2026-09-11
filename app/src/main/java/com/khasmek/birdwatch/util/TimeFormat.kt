package com.khasmek.birdwatch.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimeFormat {
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dateTime = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    fun clock(epochMs: Long): String = clock.format(Date(epochMs))
    fun dateTime(epochMs: Long): String = dateTime.format(Date(epochMs))

    /** "3s ago", "2m ago", "1h ago". */
    fun relative(epochMs: Long, now: Long = System.currentTimeMillis()): String {
        val s = ((now - epochMs) / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60}m ago"
            s < 86_400 -> "${s / 3600}h ago"
            else -> "${s / 86_400}d ago"
        }
    }

    /** "1h 05m", "12m 30s", "45s". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            h > 0 -> "%dh %02dm".format(h, m)
            m > 0 -> "%dm %02ds".format(m, sec)
            else -> "${sec}s"
        }
    }
}
