package com.khasmek.birdwatch.detection

/**
 * Folds rows for the same MAC from different radios into the single row the database keeps per
 * (session, MAC). Each source has its own [DetectionTable]; without this, a device heard by two
 * of them (a Flock AP seen by the phone's WiFi scan and by the ESP32, say) would be written by
 * both flush loops in turn, flipping source, tier and count every two seconds. Pure Kotlin.
 */
object SourceMerge {

    /** Group [rows] by MAC and merge each group. Order of the result is most recently seen first. */
    fun mergeAll(rows: List<DetectedDevice>): List<DetectedDevice> =
        rows.groupBy { DetectionTable.normalizeMac(it.macAddress) }
            .values.map { group -> if (group.size == 1) group[0] else group.reduce(::merge) }
            .sortedByDescending { it.lastSeen }

    /**
     * The row with the stronger evidence wins identity fields (source, method, type, confidence,
     * tier, matchedOn); the most recently seen row wins per-sighting fields (RSSI, GPS, channel,
     * Remote ID position); sightings add up and the span covers both.
     */
    fun merge(a: DetectedDevice, b: DetectedDevice): DetectedDevice {
        val winner = if (rank(b) > rank(a)) b else a
        val loser = if (winner === a) b else a
        val newer = if (b.lastSeen > a.lastSeen) b else a
        val older = if (newer === a) b else a
        return winner.copy(
            deviceName = winner.deviceName ?: loser.deviceName,
            ravenFirmware = winner.ravenFirmware ?: loser.ravenFirmware,
            channel = newer.channel ?: older.channel,
            rssi = newer.rssi,
            latitude = newer.latitude ?: older.latitude,
            longitude = newer.longitude ?: older.longitude,
            accuracyMeters = if (newer.latitude != null) newer.accuracyMeters else older.accuracyMeters,
            firstSeen = minOf(a.firstSeen, b.firstSeen),
            lastSeen = maxOf(a.lastSeen, b.lastSeen),
            sightings = a.sightings + b.sightings,
            uasId = newer.uasId ?: older.uasId,
            operatorId = newer.operatorId ?: older.operatorId,
            targetLatitude = newer.targetLatitude ?: older.targetLatitude,
            targetLongitude = newer.targetLongitude ?: older.targetLongitude,
            targetAltitudeM = newer.targetAltitudeM ?: older.targetAltitudeM,
            operatorLatitude = newer.operatorLatitude ?: older.operatorLatitude,
            operatorLongitude = newer.operatorLongitude ?: older.operatorLongitude,
        )
    }

    /** Higher is stronger evidence: confidence first, then firmware tier, then recency as a tie-break. */
    private fun rank(d: DetectedDevice): Long =
        (if (d.confidence == Confidence.HIGH) 1L else 0L) * 1_000_000_000_000L +
            (d.tier ?: 0).toLong() * 100_000_000_000L +
            d.lastSeen
}
