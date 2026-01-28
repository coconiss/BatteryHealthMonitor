// util/BatteryUtils.kt
package com.batteryhealth.monitor.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build

object BatteryUtils {

    fun isChargeCounterSupported(context: Context): Boolean {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return try {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getCurrentBatteryInfo(context: Context): BatteryInfo {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percentage = if (level >= 0 && scale > 0) {
            ((level / scale.toFloat()) * 100).toInt()
        } else {
            0
        }

        val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1

        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val chargerType = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "None"
        }

        val healthStatus = when (health) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
            else -> "Unknown"
        }

        return BatteryInfo(
            percentage = percentage,
            temperature = temperature,
            voltage = voltage,
            isCharging = isCharging,
            chargerType = chargerType,
            healthStatus = healthStatus
        )
    }

    fun getDeviceInfo(): DeviceInfo {
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            device = Build.DEVICE,
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT
        )
    }
}

data class BatteryInfo(
    val percentage: Int,
    val temperature: Float,
    val voltage: Int,
    val isCharging: Boolean,
    val chargerType: String,
    val healthStatus: String
)

data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val device: String,
    val androidVersion: String,
    val sdkInt: Int
)