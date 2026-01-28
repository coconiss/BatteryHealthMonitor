package com.batteryhealth.monitor

import android.app.Application
import android.content.IntentFilter
import com.batteryhealth.monitor.service.BatteryBroadcastReceiver
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BatteryHealthApplication : Application() {

    private val batteryReceiver = BatteryBroadcastReceiver()

    override fun onCreate() {
        super.onCreate()

        // Timber 초기화
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        Timber.d("BatteryHealthMonitor Application Started")

        // 배터리 상태 변화 모니터링을 위한 BroadcastReceiver 등록
        registerBatteryReceiver()

        // 자동 모니터링 기본값 설정
        val prefs = getSharedPreferences("battery_health_prefs", MODE_PRIVATE)
        if (!prefs.contains("auto_monitoring_enabled")) {
            prefs.edit().putBoolean("auto_monitoring_enabled", true).apply()
        }
    }

    private fun registerBatteryReceiver() {
        val filter = IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
            addAction(android.content.Intent.ACTION_BATTERY_CHANGED)
        }

        registerReceiver(batteryReceiver, filter)
        Timber.d("Battery broadcast receiver registered")
    }

    override fun onTerminate() {
        super.onTerminate()
        unregisterReceiver(batteryReceiver)
    }
}