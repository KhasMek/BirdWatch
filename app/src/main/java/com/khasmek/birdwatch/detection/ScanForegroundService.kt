package com.khasmek.birdwatch.detection

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
import com.khasmek.birdwatch.BirdWatchApp
import com.khasmek.birdwatch.MainActivity
import com.khasmek.birdwatch.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps a scan session alive while the app is backgrounded.
 *
 * The service does not own the radios; [com.khasmek.birdwatch.data.SessionManager] does. This
 * service goes foreground (types `connectedDevice` for BLE/USB and `location` for GPS), asks the
 * session manager to start, mirrors the live device count into an ongoing notification, and
 * switches the BLE scan mode to LOW_POWER whenever the app UI leaves the foreground.
 *
 * It stops itself as soon as the session ends, whether from the FAB, the notification's Stop
 * action, or programmatically.
 */
class ScanForegroundService : LifecycleService() {

    private val container get() = (application as BirdWatchApp).container
    private val sessionManager get() = container.sessionManager
    private val settings get() = container.settings

    private var appInForeground = true

    /** Pending switch to LOW_POWER; cancelled if the app comes back before it fires. */
    private var backgroundSwitch: Job? = null

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStart(owner: LifecycleOwner) {
            appInForeground = true
            backgroundSwitch?.cancel()
            applyScanMode()
        }

        override fun onStop(owner: LifecycleOwner) {
            appInForeground = false
            // Every mode switch restarts the scan, and the Bluetooth stack rations restarts. Wait a
            // moment before dropping to LOW_POWER so a screen that flicks off and on again costs
            // nothing; BleScanner defers anything that would still exceed the budget.
            backgroundSwitch?.cancel()
            backgroundSwitch = lifecycleScope.launch {
                delay(BACKGROUND_MODE_DELAY_MS)
                applyScanMode()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        appInForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
    }

    /** Most recent start id, so a stop never swallows a start request that arrived after it. */
    private var lastStartId = -1

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        lastStartId = startId
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop requested")
                lifecycleScope.launch { sessionManager.stopSuspending() }
                // stopSelf() happens when currentSession flips to null (observed below).
            }
            else -> {
                // Must be called promptly after startForegroundService(); do it before any real work.
                goForeground(buildNotification(DeviceCounts()))
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
        backgroundSwitch?.cancel()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ------------------------------------------------------------------

    private var observing = false

    private fun observeSession() {
        if (observing) return
        observing = true

        // Live notification: "Scanning… N devices found" with a per-category breakdown. Counts
        // are reduced before distinctUntilChanged so the 2 s re-sighting flush does not re-post.
        lifecycleScope.launch {
            sessionManager.observeCurrentDevices()
                .combine(sessionManager.currentSession) { devices, session -> devices to session }
                .filter { (_, session) -> session != null }
                .map { (devices, _) -> DeviceCounts.of(devices) }
                .distinctUntilChanged()
                .collect { counts -> notificationManager.notify(NOTIFICATION_ID, buildNotification(counts)) }
        }

        // Session ended (by any path) -> leave foreground and stop.
        lifecycleScope.launch {
            sessionManager.currentSession
                .drop(1) // skip the initial value; we only care about transitions after start
                .filter { it == null }
                .collect {
                    Log.i(TAG, "Session ended; stopping service")
                    ServiceCompat.stopForeground(this@ScanForegroundService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    // stopSelfResult: if a newer start (Stop tapped, then Start again) has already
                    // been issued, this is ignored and that start's onStartCommand runs instead of
                    // being dropped with the service.
                    if (!stopSelfResult(lastStartId)) Log.i(TAG, "A newer start is pending; staying up")
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

    /** What the notification shows: total plus the two core categories; everything else is "other". */
    private data class DeviceCounts(val total: Int = 0, val flock: Int = 0, val raven: Int = 0) {
        val other: Int get() = total - flock - raven

        fun subText(): String? = if (total == 0) null else buildList {
            if (flock > 0) add("$flock Flock")
            if (raven > 0) add("$raven Raven")
            if (other > 0) add("$other other")
        }.joinToString(" · ")

        companion object {
            fun of(devices: List<DetectedDevice>) = DeviceCounts(
                total = devices.size,
                flock = devices.count { it.deviceType.category == DeviceCategory.FLOCK_ALPR },
                raven = devices.count { it.deviceType.category == DeviceCategory.GUNSHOT_DETECTOR },
            )
        }
    }

    private fun buildNotification(counts: DeviceCounts): Notification {
        val total = counts.total
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
            .setSubText(counts.subText())
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
        private const val TAG = "BirdWatch/Service"
        const val CHANNEL_ID = "scan_status"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.khasmek.birdwatch.action.START_SCAN"
        const val ACTION_STOP = "com.khasmek.birdwatch.action.STOP_SCAN"
        const val EXTRA_SCAN_MODE = "scan_mode"
        private const val BACKGROUND_MODE_DELAY_MS = 10_000L

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
