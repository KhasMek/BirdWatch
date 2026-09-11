package com.khasmek.birdwatch.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One scan session: from the user pressing start until stop (or the app dying). */
@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey val id: String,
    /** Epoch millis. */
    val startedAt: Long,
    /** Epoch millis; null while the session is still running. */
    val endedAt: Long? = null,
) {
    val isActive: Boolean get() = endedAt == null

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
