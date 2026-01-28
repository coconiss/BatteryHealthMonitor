// service/BootCompletedReceiver.kt (새 파일)
package com.batteryhealth.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import timber.log.Timber

class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.i("Device boot completed, starting battery monitoring")

            // 자동 모니터링 설정 확인
            val prefs = context.getSharedPreferences("battery_health_prefs", Context.MODE_PRIVATE)
            val autoStart = prefs.getBoolean("auto_start_monitoring", true)

            if (autoStart) {
                // 배터리 상태 확인 후 충전 중이면 서비스 시작
                val batteryIntent = context.registerReceiver(
                    null,
                    android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                )

                val status = batteryIntent?.getIntExtra(
                    android.os.BatteryManager.EXTRA_STATUS,
                    -1
                ) ?: -1

                val isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == android.os.BatteryManager.BATTERY_STATUS_FULL

                if (isCharging) {
                    startMonitoringService(context)
                }
            }
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
}