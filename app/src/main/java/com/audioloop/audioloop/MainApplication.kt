package com.audioloop.audioloop

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MainApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // You can add any application-specific initialization here if needed.
    }
}