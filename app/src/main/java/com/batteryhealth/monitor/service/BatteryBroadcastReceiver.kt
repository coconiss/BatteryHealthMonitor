package com.batteryhealth.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import timber.log.Timber

class BatteryBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("BroadcastReceiver.onReceive: ${intent.action}")

        // 자동 모니터링 설정 확인
        val prefs = context.getSharedPreferences("battery_health_prefs", Context.MODE_PRIVATE)
        val autoMonitoring = prefs.getBoolean("auto_monitoring_enabled", true)

        if (!autoMonitoring) {
            Timber.d("Auto monitoring is disabled")
            return
        }

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Timber.i("🔌 Power connected - starting monitoring")
                startMonitoringService(context)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                Timber.i("🔌 Power disconnected - stopping monitoring")
                stopMonitoringService(context)
            }

            // ACTION_BATTERY_CHANGED 제거 - Service에서 직접 관리하여 중복 방지
        }
    }

    private fun startMonitoringService(context: Context) {
        val serviceIntent = Intent(context, BatteryMonitoringService::class.java).apply {
            action = BatteryMonitoringService.ACTION_START_MONITORING
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    private fun stopMonitoringService(context: Context) {
        val serviceIntent = Intent(context, BatteryMonitoringService::class.java).apply {
            action = BatteryMonitoringService.ACTION_STOP_MONITORING
        }
        context.startService(serviceIntent)
    }
}