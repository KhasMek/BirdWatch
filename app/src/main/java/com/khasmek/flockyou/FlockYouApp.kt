package com.khasmek.flockyou

import android.app.Application
import android.content.Context
import com.khasmek.flockyou.detection.BleScanner

/**
 * Manual dependency container. Singletons are created lazily on first use so that nothing
 * touches Bluetooth before permissions are granted.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val bleScanner: BleScanner by lazy { BleScanner(appContext) }
}

class FlockYouApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = AppContainer(this)
    }

    companion object {
        lateinit var instance: FlockYouApp
            private set
    }
}
