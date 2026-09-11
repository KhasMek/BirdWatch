package com.khasmek.birdwatch.detection

/**
 * Structured reason behind a scanner error or warning, so the UI can offer the right fix
 * (a "Turn on" button, a settings deep link) without parsing message text.
 */
enum class ScanIssue {
    PERMISSION_DENIED,
    NO_ADAPTER,
    BLUETOOTH_OFF,
    LOCATION_OFF,
    SCAN_FAILED,
    WIFI_OFF,
    WIFI_THROTTLED,
    NO_WIFI,
}
