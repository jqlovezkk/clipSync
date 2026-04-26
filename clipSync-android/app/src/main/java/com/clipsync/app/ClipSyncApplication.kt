package com.clipsync.app

import android.app.Application
import com.clipsync.app.data.AppDatabase

/**
 * Application entry point.
 * Initializes shared resources like the database.
 */
class ClipSyncApplication : Application() {

    val database: AppDatabase by lazy {
        AppDatabase.getInstance(this)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: ClipSyncApplication
            private set
    }
}
