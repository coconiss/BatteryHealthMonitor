// util/BatteryUtils.kt 수정
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
     * 배터리 설계 용량을 여러 방법으로 시도하여 가져옴
     * 설정 > 배터리에 표시되는 값과 동일해야 함
     */
    fun getBatteryCapacity(context: Context): Int? {
        Timber.d("Attempting to get battery design capacity...")

        // 방법 1: PowerProfile을 통한 배터리 용량 가져오기
        getBatteryCapacityFromPowerProfile(context)?.let { capacity ->
            Timber.i("Battery capacity from PowerProfile: $capacity mAh")
            // PowerProfile 값이 합리적인 범위인지 확인
            if (capacity in 1000..10000) {
                return capacity
            } else {
                Timber.w("PowerProfile returned unrealistic value: $capacity")
            }
        }

        // 방법 2: 커널 파일 시스템에서 설계 용량 가져오기 (더 정확)
        getBatteryCapacityFromKernel()?.let { capacity ->
            Timber.i("Battery capacity from kernel: $capacity mAh")
            if (capacity in 1000..10000) {
                return capacity
            }
        }

        // 방법 3: 시스템 프로퍼티에서 가져오기
        getBatteryCapacityFromSystemProperty()?.let { capacity ->
            Timber.i("Battery capacity from system property: $capacity mAh")
            if (capacity in 1000..10000) {
                return capacity
            }
        }

        // 방법 4: Reflection을 통한 다른 메서드 시도
        getBatteryCapacityFromBatteryManager(context)?.let { capacity ->
            Timber.i("Battery capacity from BatteryManager: $capacity mAh")
            if (capacity in 1000..10000) {
                return capacity
            }
        }

        Timber.w("Failed to get battery capacity from all methods")
        return null
    }

    /**
     * 방법 1: PowerProfile을 통해 배터리 설계 용량 가져오기
     */
    private fun getBatteryCapacityFromPowerProfile(context: Context): Int? {
        return try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val constructor = powerProfileClass.getConstructor(Context::class.java)
            val powerProfile = constructor.newInstance(context)

            // getBatteryCapacity 메서드 호출
            val getBatteryCapacity = powerProfileClass.getMethod("getBatteryCapacity")
            val capacity = getBatteryCapacity.invoke(powerProfile) as Double

            Timber.d("PowerProfile raw value: $capacity")

            // Double을 Int로 변환 (반올림)
            val capacityInt = capacity.toInt()

            capacityInt
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery capacity from PowerProfile")
            null
        }
    }

    /**
     * 방법 2: 커널 파일 시스템에서 배터리 설계 용량 가져오기
     * 이 방법이 가장 정확할 수 있음
     */
    private fun getBatteryCapacityFromKernel(): Int? {
        val paths = listOf(
            // 설계 용량 (design capacity)
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/charge_design",
            // 일부 기기에서는 다른 경로 사용
            "/sys/class/power_supply/battery/fg_fullcapnom",
            "/sys/class/power_supply/bms/battery_capacity"
        )

        for (path in paths) {
            try {
                val file = File(path)
                if (file.exists() && file.canRead()) {
                    val content = file.readText().trim()
                    Timber.d("Read from $path: $content")

                    val value = content.toLongOrNull()
                    value?.let {
                        // 값의 단위 확인 및 변환
                        val capacity = when {
                            // µAh 단위 (1,000,000 이상)
                            it > 1000000 -> (it / 1000).toInt()
                            // 이미 mAh 단위 (1000-10000 범위)
                            it in 1000..10000 -> it.toInt()
                            // µAh 단위일 가능성 (100,000 이상)
                            it > 100000 -> (it / 1000).toInt()
                            else -> null
                        }

                        if (capacity != null && capacity in 1000..10000) {
                            Timber.i("Valid capacity from $path: $capacity mAh")
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
     * 방법 3: 시스템 프로퍼티에서 배터리 용량 가져오기
     */
    private fun getBatteryCapacityFromSystemProperty(): Int? {
        val properties = listOf(
            "ro.config.battery_capacity",
            "persist.sys.battery.capacity",
            "ro.bat.capacity",
            "persist.vendor.battery.capacity"
        )

        for (property in properties) {
            try {
                val process = Runtime.getRuntime().exec("getprop $property")
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor()

                Timber.d("Property $property: $value")

                value.toIntOrNull()?.let { capacity ->
                    if (capacity in 1000..10000) {
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
     * 방법 4: BatteryManager의 여러 메서드 시도
     */
    private fun getBatteryCapacityFromBatteryManager(context: Context): Int? {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            // Android 9 이상에서 사용 가능한 메서드
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val chargeCounter = batteryManager.getLongProperty(
                    BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
                )
                val capacity = batteryManager.getIntProperty(
                    BatteryManager.BATTERY_PROPERTY_CAPACITY
                )

                Timber.d("BatteryManager - ChargeCounter: $chargeCounter, Capacity: $capacity%")

                // CHARGE_COUNTER를 현재 퍼센트로 나누어 전체 용량 추정
                if (chargeCounter > 0 && capacity > 0) {
                    val estimatedTotal = (chargeCounter / 1000.0 / capacity * 100).toInt()
                    if (estimatedTotal in 1000..10000) {
                        Timber.i("Estimated capacity from BatteryManager: $estimatedTotal mAh")
                        return estimatedTotal
                    }
                }
            }

            null
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