package com.khasmek.flockyou.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permission set required for BLE scanning + GPS tagging, computed per Android version.
 *
 * - API 31+ : BLUETOOTH_SCAN / BLUETOOTH_CONNECT are runtime permissions.
 * - API 26-30: BLUETOOTH / BLUETOOTH_ADMIN are install-time (declared in the manifest with maxSdkVersion=30),
 *              so only location must be requested at runtime.
 * - All versions: ACCESS_FINE_LOCATION is mandatory to receive any BLE scan results.
 * - API 33+ : POST_NOTIFICATIONS is needed for the foreground-service notification to be visible.
 *             It is treated as optional: scanning still works without it.
 */
object Permissions {

    /** Permissions without which the app cannot scan at all. */
    val essential: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Nice-to-have permissions that we request but do not block on. */
    val optional: List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Everything we ask for in the single up-front request. */
    val all: List<String> = essential + optional

    fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    fun allEssentialGranted(context: Context): Boolean =
        essential.all { isGranted(context, it) }

    fun missingEssential(context: Context): List<String> =
        essential.filterNot { isGranted(context, it) }

    /** Human-readable label for a permission string, used on the rationale screen. */
    fun label(permission: String): String = when (permission) {
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION -> "Location (required by Android for BLE scanning and for GPS tagging)"
        Manifest.permission.BLUETOOTH_SCAN -> "Nearby devices — Bluetooth scan"
        Manifest.permission.BLUETOOTH_CONNECT -> "Nearby devices — Bluetooth connect"
        Manifest.permission.POST_NOTIFICATIONS -> "Notifications (scan status while in background)"
        else -> permission.substringAfterLast('.')
    }
}
