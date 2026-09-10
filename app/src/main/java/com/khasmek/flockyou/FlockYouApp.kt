package com.khasmek.flockyou

import android.app.Application

/**
 * Application class. Later phases will use this as the composition root for
 * manual dependency injection (database, scanner, location provider, etc.).
 */
class FlockYouApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: FlockYouApp
            private set
    }
}
