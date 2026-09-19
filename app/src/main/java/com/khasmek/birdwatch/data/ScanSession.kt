package com.khasmek.birdwatch.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey

/** How a session came to exist on this phone. Stored by name (schema v6). */
enum class SessionOrigin(val label: String) {
    /** A scan run on this phone. */
    LIVE("Live scan"),
    /** Pulled from the ESP32's memory or flash; no clock, no GPS. */
    ESP32_IMPORT("ESP32 import"),
    /** "Import session…" from one of the app's export files. */
    FILE_IMPORT("Imported"),
    /** Added by "Restore…" from a backup. */
    RESTORE("Restored"),
    ;

    companion object {
        /**
         * Origin for a session read from a file: an ESP32 import stays one (it explains the
         * missing GPS and the anchored timestamps); anything else becomes [fallback], because a
         * file is never a live scan on this phone.
         */
        fun importedFrom(declared: SessionOrigin?, fallback: SessionOrigin): SessionOrigin =
            if (declared == ESP32_IMPORT) ESP32_IMPORT else fallback
    }
}

/** One scan session: from the user pressing start until stop (or the app dying), or an import. */
@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey val id: String,
    /** Epoch millis. */
    val startedAt: Long,
    /** Epoch millis; null while the session is still running. */
    val endedAt: Long? = null,
    /**
     * Optional display name (schema v3). The user can set one ("Rename…"); ESP32 imports get
     * "ESP32 import (memory|flash)" by default so the two tables stay distinguishable.
     */
    val label: String? = null,
    /** Where the session came from (schema v6); drives the "imported" hints, not the label. */
    @ColumnInfo(defaultValue = "LIVE")
    val origin: SessionOrigin = SessionOrigin.LIVE,
) {
    val isActive: Boolean get() = endedAt == null
    val isImported: Boolean get() = origin != SessionOrigin.LIVE

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
