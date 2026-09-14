package com.khasmek.birdwatch.detection

/**
 * Bookkeeping for how often a BLE scan may be stopped and started again. Pure Kotlin so the
 * arithmetic is unit-tested; [BleScanner] owns one and calls it under its lock.
 *
 * The Bluetooth stack refuses `startScan` when an app's fifth most recent scan stop is younger
 * than 30 s ("scanning too frequently") and reports that only asynchronously. So before a
 * stop + start we require that at most [maxStopsBeforeRestart] of our own stops fall inside
 * [windowMs]; after the new stop that leaves four, one short of the limit.
 */
class ScanRestartBudget(
    private val windowMs: Long = 31_000L, // the stack's 30 s plus a margin for clock skew
    private val maxStopsBeforeRestart: Int = 3,
    private val minWaitMs: Long = 250L,
) {
    private val stops = ArrayDeque<Long>()

    fun recordStop(now: Long) {
        stops.addLast(now)
        prune(now)
    }

    /** 0 when a stop + start is safe right now, otherwise how many ms to wait. */
    fun msUntilRestartAllowed(now: Long): Long {
        prune(now)
        val excess = stops.size - maxStopsBeforeRestart
        if (excess <= 0) return 0
        // The (excess)th oldest stop must age out of the window first.
        return (stops.elementAt(excess - 1) + windowMs - now + 1).coerceAtLeast(minWaitMs)
    }

    fun reset() = stops.clear()

    private fun prune(now: Long) {
        while (stops.isNotEmpty() && now - stops.first() > windowMs) stops.removeFirst()
    }
}
