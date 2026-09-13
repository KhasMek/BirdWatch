package com.khasmek.birdwatch.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One scan session: from the user pressing start until stop (or the app dying), or an import. */
@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey val id: String,
    /** Epoch millis. */
    val startedAt: Long,
    /** Epoch millis; null while the session is still running. */
    val endedAt: Long? = null,
    /**
     * Optional display name (schema v3). Set for sessions imported from the ESP32's memory or
     * flash, e.g. "ESP32 import (flash)", so they are distinguishable from live scans.
     */
    val label: String? = null,
) {
    val isActive: Boolean get() = endedAt == null
    val isImported: Boolean get() = label != null

    /** Duration so far (active) or total (ended), in millis. */
    fun durationMillis(now: Long = System.currentTimeMillis()): Long = (endedAt ?: now) - startedAt
}

/** A session plus aggregate counts, for the Sessions list. Produced by [SessionDao.observeSummaries]. */
data class SessionSummary(
    @Embedded val session: ScanSession,
    val deviceCount: Int,
    val flockCount: Int,
    val ravenCount: Int,
) {
    /** Hits from opt-in signature packs (law enforcement, wearables, ...). */
    val otherCount: Int get() = deviceCount - flockCount - ravenCount
}
