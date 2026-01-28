// util/BatteryUtils.kt 수정 및 확장
package com.batteryhealth.monitor.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import timber.log.Timber
import java.io.File

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

    /**
     * 배터리 용량을 여러 방법으로 시도하여 가져옴
     */
    fun getBatteryCapacity(context: Context): Int? {
        Timber.d("Attempting to get battery capacity...")

        // 방법 1: PowerProfile을 통한 배터리 용량 가져오기 (가장 정확)
        getBatteryCapacityFromPowerProfile(context)?.let {
            Timber.i("Battery capacity from PowerProfile: $it mAh")
            return it
        }

        // 방법 2: 시스템 프로퍼티에서 가져오기
        getBatteryCapacityFromSystemProperty()?.let {
            Timber.i("Battery capacity from system property: $it mAh")
            return it
        }

        // 방법 3: 커널 파일 시스템에서 가져오기
        getBatteryCapacityFromKernel()?.let {
            Timber.i("Battery capacity from kernel: $it mAh")
            return it
        }

        // 방법 4: Reflection을 통한 BatteryManager 메서드 호출
        getBatteryCapacityFromBatteryManager(context)?.let {
            Timber.i("Battery capacity from BatteryManager: $it mAh")
            return it
        }

        Timber.w("Failed to get battery capacity from all methods")
        return null
    }

    /**
     * 방법 1: PowerProfile을 통해 배터리 용량 가져오기
     * 설정 > 배터리 > 배터리 정보에 표시되는 값과 동일
     */
    private fun getBatteryCapacityFromPowerProfile(context: Context): Int? {
        return try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val constructor = powerProfileClass.getConstructor(Context::class.java)
            val powerProfile = constructor.newInstance(context)

            val getBatteryCapacity = powerProfileClass.getMethod("getBatteryCapacity")
            val capacity = getBatteryCapacity.invoke(powerProfile) as Double

            capacity.toInt()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery capacity from PowerProfile")
            null
        }
    }

    /**
     * 방법 2: 시스템 프로퍼티에서 배터리 용량 가져오기
     */
    private fun getBatteryCapacityFromSystemProperty(): Int? {
        val properties = listOf(
            "ro.config.battery_capacity",
            "persist.sys.battery.capacity",
            "ro.bat.capacity"
        )

        for (property in properties) {
            try {
                val process = Runtime.getRuntime().exec("getprop $property")
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor()

                value.toIntOrNull()?.let { capacity ->
                    if (capacity > 0) {
                        return capacity
                    }
                }
            } catch (e: Exception) {
                Timber.v(e, "Failed to get property $property")
            }
        }

        return null
    }

    /**
     * 방법 3: 커널 파일 시스템에서 배터리 용량 가져오기
     */
    private fun getBatteryCapacityFromKernel(): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/charge_counter_shadow",
            "/sys/class/power_supply/battery/fg_fullcapnom"
        )

        for (path in paths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val value = file.readText().trim().toLongOrNull()
                    value?.let {
                        // 값이 µAh 단위인 경우 mAh로 변환
                        val capacity = if (it > 100000) {
                            (it / 1000).toInt()
                        } else {
                            it.toInt()
                        }

                        if (capacity in 1000..10000) {
                            return capacity
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.v(e, "Failed to read from $path")
            }
        }

        return null
    }

    /**
     * 방법 4: BatteryManager의 숨겨진 메서드 사용
     */
    private fun getBatteryCapacityFromBatteryManager(context: Context): Int? {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batteryManagerClass = batteryManager.javaClass

            // computeChargeTimeRemaining 메서드가 있으면 내부적으로 용량 정보를 가지고 있음
            val getCapacity = batteryManagerClass.getMethod("getIntProperty", Int::class.java)

            // BatteryManager.BATTERY_PROPERTY_CAPACITY (숨겨진 상수)
            val capacity = getCapacity.invoke(batteryManager, 4) as Int

            if (capacity > 0) capacity else null
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery capacity from BatteryManager")
            null
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