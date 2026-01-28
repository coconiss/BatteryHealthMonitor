// BatteryHealthApplication.kt
package com.batteryhealth.monitor

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BatteryHealthApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Timber 초기화
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("BatteryHealthMonitor Application Started")
    }
}