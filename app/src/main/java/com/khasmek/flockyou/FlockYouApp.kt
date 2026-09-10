package com.khasmek.flockyou

import android.app.Application
import android.content.Context
import com.khasmek.flockyou.audio.AlertSounds
import com.khasmek.flockyou.data.AppSettings
import com.khasmek.flockyou.data.DetectionDatabase
import com.khasmek.flockyou.data.SessionManager
import com.khasmek.flockyou.detection.BleScanner
import com.khasmek.flockyou.location.LocationProvider
import com.khasmek.flockyou.usb.UsbCompanion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency container. Singletons are created lazily on first use so that nothing
 * touches Bluetooth or GPS before permissions are granted.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    /** Process-wide scope for background work that outlives any screen (persistence, radios). */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val settings: AppSettings by lazy { AppSettings(appContext) }
    val database: DetectionDatabase by lazy { DetectionDatabase.build(appContext) }
    val bleScanner: BleScanner by lazy { BleScanner(appContext) }
    val usbCompanion: UsbCompanion by lazy { UsbCompanion(appContext, appScope) }
    val locationProvider: LocationProvider by lazy { LocationProvider(appContext) }
    val sessionManager: SessionManager by lazy {
        SessionManager(database, bleScanner, usbCompanion, locationProvider, appScope)
    }
    val alertSounds: AlertSounds by lazy { AlertSounds(appContext, settings) }
}

class FlockYouApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
        // Audio alerts follow every session regardless of which screen is open.
        container.alertSounds.start(container.sessionManager.newDetections, container.appScope)
    }

    companion object {
        lateinit var instance: FlockYouApp
            private set
    }
}
