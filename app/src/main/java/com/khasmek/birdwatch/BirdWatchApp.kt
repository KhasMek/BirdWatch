package com.khasmek.birdwatch

import android.app.Application
import android.content.Context
import com.khasmek.birdwatch.audio.AlertSounds
import com.khasmek.birdwatch.data.AppSettings
import com.khasmek.birdwatch.data.BackupManager
import com.khasmek.birdwatch.data.DetectionDatabase
import com.khasmek.birdwatch.data.DeviceEditor
import com.khasmek.birdwatch.data.ExportManager
import com.khasmek.birdwatch.data.SecureSettings
import com.khasmek.birdwatch.data.SessionManager
import com.khasmek.birdwatch.detection.BleScanner
import com.khasmek.birdwatch.location.LocationProvider
import com.khasmek.birdwatch.usb.UsbCompanion
import com.khasmek.birdwatch.wifi.WifiApScanner
import kotlinx.coroutines.CoroutineScope
import com.khasmek.birdwatch.util.CrashRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Singletons are lazy, but `BirdWatchApp.onCreate` touches
 * `sessionManager` (and so builds every radio wrapper) right away so their broadcast receivers
 * are registered from the start. That is safe before permissions are granted: the constructors
 * only register receivers and read system-service handles; nothing scans, opens a port or asks
 * for a fix until a session starts, which the permission gate in `MainActivity` sits in front of.
 */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    /** Process-wide scope for background work that outlives any screen (persistence, radios). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(appContext) }
    val secureSettings: SecureSettings by lazy { SecureSettings(appContext, appScope) }
    val database: DetectionDatabase by lazy { DetectionDatabase.build(appContext) }
    val bleScanner: BleScanner by lazy { BleScanner(appContext) }
    val usbCompanion: UsbCompanion by lazy { UsbCompanion(appContext, appScope) }
    val wifiApScanner: WifiApScanner by lazy { WifiApScanner(appContext, appScope) }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val sessionManager: SessionManager by lazy {
        SessionManager(appContext, database, settings, bleScanner, usbCompanion, wifiApScanner, locationProvider, appScope)
    }
    val alertSounds: AlertSounds by lazy { AlertSounds(appContext, settings) }
    val exportManager: ExportManager by lazy { ExportManager(appContext, database) }
    val backupManager: BackupManager by lazy { BackupManager(appContext, database) }
    val deviceEditor: DeviceEditor by lazy { DeviceEditor(database, sessionManager) }
}

class BirdWatchApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Record uncaught exceptions (scrubbed) for Settings > Copy diagnostics; Android's own
        // handler still runs afterwards.
        CrashRecorder.install(this, BuildConfig.VERSION_NAME)
        container = AppContainer(this)
        // Audio alerts follow every session regardless of which screen is open.
        container.alertSounds.start(container.sessionManager.newDetections, container.appScope, container.sessionManager::priorSessions)
        // Signature-pack toggles apply to both phone radios immediately, mid-session included.
        container.appScope.launch {
            container.settings.enabledPacks.collect {
                container.bleScanner.enabledPacks = it
                container.wifiApScanner.enabledPacks = it
            }
        }
    }

    companion object {
        lateinit var instance: BirdWatchApp
            private set
    }
}
