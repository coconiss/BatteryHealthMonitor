// service/BatteryBroadcastReceiver.kt
package com.batteryhealth.monitor.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import timber.log.Timber

class BatteryBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                Timber.i("Power connected")
                startMonitoringService(context)
            }

            Intent.ACTION_POWER_DISCONNECTED -> {
                Timber.i("Power disconnected")
                stopMonitoringService(context)
            }

            Intent.ACTION_BATTERY_CHANGED -> {
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (!isCharging) {
                    stopMonitoringService(context)
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

    private fun stopMonitoringService(context: Context) {
        val serviceIntent = Intent(context, BatteryMonitoringService::class.java).apply {
            action = BatteryMonitoringService.ACTION_STOP_MONITORING
        }
        context.startService(serviceIntent)
    }
}