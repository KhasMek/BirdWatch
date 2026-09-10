package com.khasmek.flockyou.detection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.khasmek.flockyou.FlockYouApp
import com.khasmek.flockyou.MainActivity
import com.khasmek.flockyou.R
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Keeps a scan session alive while the app is backgrounded.
 *
 * The service does not own the radios; [com.khasmek.flockyou.data.SessionManager] does. This
 * service goes foreground (types `connectedDevice` for BLE/USB and `location` for GPS), asks the
 * session manager to start, mirrors the live device count into an ongoing notification, and
 * switches the BLE scan mode to LOW_POWER whenever the app UI leaves the foreground.
 *
 * It stops itself as soon as the session ends, whether from the FAB, the notification's Stop
 * action, or programmatically.
 */
class ScanForegroundService : LifecycleService() {

    private val container get() = (application as FlockYouApp).container
    private val sessionManager get() = container.sessionManager
    private val settings get() = container.settings

    private var appInForeground = true

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appInForeground = true
            applyScanMode()
        }

        override fun onStop(owner: LifecycleOwner) {
            appInForeground = false
            applyScanMode()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                lifecycleScope.launch { sessionManager.stopSuspending() }
                // stopSelf() happens when currentSession flips to null (observed below).
            }
            else -> {
                // Must be called promptly after startForegroundService(); do it before any real work.
                goForeground(buildNotification(0, 0, 0))
                val mode = intent?.getIntExtra(EXTRA_SCAN_MODE, settings.scanMode) ?: settings.scanMode
                lifecycleScope.launch {
                    sessionManager.startSuspending(mode)
                    applyScanMode()
                }
                observeSession()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ------------------------------------------------------------------

    private var observing = false

    private fun observeSession() {
        if (observing) return
        observing = true

        // Live notification: "Scanning… N devices found" with Flock / Raven breakdown.
        lifecycleScope.launch {
            sessionManager.observeCurrentDevices()
                .combine(sessionManager.currentSession) { devices, session -> devices to session }
                .filter { (_, session) -> session != null }
                .distinctUntilChanged()
                .collect { (devices, _) ->
                    val flock = devices.count { it.deviceType == DeviceType.FLOCK }
                    val raven = devices.size - flock
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(devices.size, flock, raven))
                }
        }

        // Session ended (by any path) -> leave foreground and stop.
        lifecycleScope.launch {
            sessionManager.currentSession
                .drop(1) // skip the initial value; we only care about transitions after start
                .filter { it == null }
                .collect {
                    Log.i(TAG, "Session ended; stopping service")
                    ServiceCompat.stopForeground(this@ScanForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
        }

        // Scan-mode preference changed while running.
        lifecycleScope.launch {
            settings.lowPowerScan.drop(1).collect { applyScanMode() }
        }
    }

    /** LOW_LATENCY (or the user's preference) in the foreground; LOW_POWER in the background. */
    private fun applyScanMode() {
        if (!sessionManager.isActive) return
        val mode = if (appInForeground) settings.scanMode else ScanSettings.SCAN_MODE_LOW_POWER
        container.bleScanner.setScanMode(mode)
    }

    private fun goForeground(notification: Notification) {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, types)
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scan status",
            NotificationManager.IMPORTANCE_LOW, // silent; detection chirps come from AlertSounds
        ).apply {
            description = "Shown while a scan session is running"
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(total: Int, flock: Int, raven: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ScanForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (total) {
            0 -> "Scanning… no devices yet"
            1 -> "Scanning… 1 device found"
            else -> "Scanning… $total devices found"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_scan)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSubText(if (total > 0) "$flock Flock · $raven Raven" else null)
            .setContentIntent(openApp)
            .addAction(0, "Stop", stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val TAG = "FlockYou/Service"
        const val CHANNEL_ID = "scan_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.khasmek.flockyou.action.START_SCAN"
        const val ACTION_STOP = "com.khasmek.flockyou.action.STOP_SCAN"
        const val EXTRA_SCAN_MODE = "scan_mode"

        /** Start (or re-deliver start to) the service. Must be called while the app is visible. */
        fun start(context: Context, scanMode: Int) {
            val intent = Intent(context, ScanForegroundService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SCAN_MODE, scanMode)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScanForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
