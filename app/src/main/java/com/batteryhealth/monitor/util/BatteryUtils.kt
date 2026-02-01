// util/BatteryUtils.kt 전체 수정
package com.batteryhealth.monitor.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import timber.log.Timber
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

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
     */
    fun getBatteryCapacity(context: Context): Int? {
        Timber.d("Attempting to get battery design capacity...")

        // 방법 1: dumpsys battery 명령어로 가져오기 (가장 정확)
        getBatteryCapacityFromDumpsys()?.let { capacity ->
            Timber.i("Battery capacity from dumpsys: $capacity mAh")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        // 방법 2: 시스템 프로퍼티에서 가져오기
        getBatteryCapacityFromSystemProperty()?.let { capacity ->
            Timber.i("Battery capacity from system property: $capacity mAh")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        // 방법 3: su 권한으로 커널 파일 읽기 시도
        getBatteryCapacityFromKernelWithSu()?.let { capacity ->
            Timber.i("Battery capacity from kernel (su): $capacity mAh")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        // 방법 4: 커널 파일 시스템에서 직접 읽기
        getBatteryCapacityFromKernel()?.let { capacity ->
            Timber.i("Battery capacity from kernel: $capacity mAh")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        // 방법 5: PowerProfile (정확하지 않을 수 있음)
        getBatteryCapacityFromPowerProfile(context)?.let { capacity ->
            Timber.w("Battery capacity from PowerProfile: $capacity mAh (may not be accurate)")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        // 방법 6: CHARGE_COUNTER 기반 역산 (현재 충전 상태에서 추정)
        getBatteryCapacityFromChargeCounter(context)?.let { capacity ->
            Timber.i("Battery capacity estimated from CHARGE_COUNTER: $capacity mAh")
            if (capacity in 1000..15000) {
                return capacity
            }
        }

        Timber.w("Failed to get battery capacity from all methods")
        return null
    }

    /**
     * 방법 1: dumpsys battery 명령어로 배터리 정보 가져오기
     * 이 방법이 가장 정확하고 설정 앱과 동일한 값을 반환
     */
    private fun getBatteryCapacityFromDumpsys(): Int? {
        return try {
            val process = Runtime.getRuntime().exec("dumpsys battery")
            val reader = BufferedReader(InputStreamReader(process.inputStream))

            var capacity: Int? = null
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                line?.let {
                    Timber.v("dumpsys battery: $it")

                    // "Max charging current:", "Max charging voltage:", "Charge counter:" 등 찾기
                    when {
                        // "charge counter" 항목 찾기
                        it.contains("charge counter", ignoreCase = true) -> {
                            val parts = it.split(":")
                            if (parts.size >= 2) {
                                val value = parts[1].trim().toLongOrNull()
                                value?.let { counter ->
                                    // µAh를 mAh로 변환
                                    val mah = (counter / 1000).toInt()
                                    if (mah in 1000..15000) {
                                        capacity = mah
                                    }
                                }
                            }
                        }
                        // "Charge full design" 찾기
                        it.contains("charge full design", ignoreCase = true) -> {
                            val parts = it.split(":")
                            if (parts.size >= 2) {
                                val value = parts[1].trim().toLongOrNull()
                                value?.let { fullDesign ->
                                    val mah = if (fullDesign > 100000) {
                                        (fullDesign / 1000).toInt()
                                    } else {
                                        fullDesign.toInt()
                                    }
                                    if (mah in 1000..15000) {
                                        capacity = mah
                                    }
                                }
                            }
                        }
                    }
                }
            }

            reader.close()
            process.waitFor()

            capacity
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery capacity from dumpsys")
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
            "ro.bat.capacity",
            "persist.vendor.battery.capacity",
            "ro.config.hw_battery_capacity"
        )

        for (property in properties) {
            try {
                val process = Runtime.getRuntime().exec("getprop $property")
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor()

                if (value.isNotEmpty()) {
                    Timber.d("Property $property: $value")

                    value.toIntOrNull()?.let { capacity ->
                        if (capacity in 1000..15000) {
                            return capacity
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.v(e, "Failed to get property $property")
            }
        }

        return null
    }

    /**
     * 방법 3: su 권한으로 커널 파일 읽기
     */
    private fun getBatteryCapacityFromKernelWithSu(): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/fg_fullcapnom"
        )

        for (path in paths) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $path"))
                val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
                process.waitFor()

                if (value.isNotEmpty()) {
                    Timber.d("Read from $path (su): $value")

                    value.toLongOrNull()?.let { capacity ->
                        val mah = when {
                            capacity > 1000000 -> (capacity / 1000).toInt()
                            capacity > 100000 -> (capacity / 1000).toInt()
                            capacity in 1000..15000 -> capacity.toInt()
                            else -> null
                        }

                        if (mah != null && mah in 1000..15000) {
                            return mah
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.v(e, "Failed to read $path with su")
            }
        }

        return null
    }

    /**
     * 방법 4: 커널 파일 시스템에서 배터리 설계 용량 가져오기
     */
    private fun getBatteryCapacityFromKernel(): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design",
            "/sys/class/power_supply/bms/charge_full_design",
            "/sys/class/power_supply/battery/charge_design",
            "/sys/class/power_supply/battery/fg_fullcapnom",
            "/sys/class/power_supply/bms/battery_capacity",
            "/sys/class/power_supply/battery/constant_charge_current_max",
            "/sys/class/qpnp-fg/charge_full_design"
        )

        for (path in paths) {
            try {
                val file = File(path)

                if (file.exists()) {
                    Timber.d("Found file: $path, canRead: ${file.canRead()}")

                    if (file.canRead()) {
                        val content = file.readText().trim()
                        Timber.d("Read from $path: $content")

                        val value = content.toLongOrNull()
                        value?.let {
                            val capacity = when {
                                it > 1000000 -> (it / 1000).toInt()
                                it > 100000 -> (it / 1000).toInt()
                                it in 1000..15000 -> it.toInt()
                                else -> null
                            }

                            if (capacity != null && capacity in 1000..15000) {
                                return capacity
                            }
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
     * 방법 5: PowerProfile을 통해 배터리 설계 용량 가져오기
     * 주의: 이 값이 항상 정확하지는 않음
     */
    private fun getBatteryCapacityFromPowerProfile(context: Context): Int? {
        return try {
            val powerProfileClass = Class.forName("com.android.internal.os.PowerProfile")
            val constructor = powerProfileClass.getConstructor(Context::class.java)
            val powerProfile = constructor.newInstance(context)

            val getBatteryCapacity = powerProfileClass.getMethod("getBatteryCapacity")
            val capacity = getBatteryCapacity.invoke(powerProfile) as Double

            Timber.d("PowerProfile raw value: $capacity")

            capacity.toInt()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery capacity from PowerProfile")
            null
        }
    }

    /**
     * 방법 6: CHARGE_COUNTER를 이용한 역산
     * 현재 배터리 퍼센트와 CHARGE_COUNTER를 이용하여 전체 용량 추정
     */
    private fun getBatteryCapacityFromChargeCounter(context: Context): Int? {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

            // 현재 충전량 (µAh)
            val chargeCounter = batteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
            )

            // 현재 배터리 퍼센트
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1

            if (level > 0 && scale > 0 && chargeCounter > 0) {
                val percentage = (level.toFloat() / scale.toFloat()) * 100

                // 20% 이하나 95% 이상에서는 정확도가 떨어질 수 있음
                if (percentage in 20.0..95.0) {
                    // 전체 용량 = (현재 충전량 / 현재 퍼센트) * 100
                    val estimatedCapacity = ((chargeCounter / 1000.0) / percentage * 100).toInt()

                    Timber.d("Estimated from CHARGE_COUNTER: $estimatedCapacity mAh (at $percentage%)")

                    if (estimatedCapacity in 1000..15000) {
                        return estimatedCapacity
                    }
                }
            }

            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to estimate battery capacity from CHARGE_COUNTER")
            null
        }
    }

    /**
     * 배터리 실제 현재 최대 용량 추정
     * 여러 충전 세션 데이터를 분석하여 현재 배터리의 실제 용량 계산
     */
    fun estimateActualBatteryCapacity(context: Context, percentage: Int): Int? {
        if (percentage !in 20..95) {
            Timber.w("Battery percentage $percentage% is outside reliable range (20-95%)")
            return null
        }

        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val chargeCounter = batteryManager.getLongProperty(
                BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER
            )

            if (chargeCounter > 0) {
                val estimatedCapacity = ((chargeCounter / 1000.0) / percentage * 100).toInt()

                if (estimatedCapacity in 1000..15000) {
                    estimatedCapacity
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to estimate actual battery capacity")
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

    /**
     * 배터리 용량 감지 디버그 정보
     */
    fun getBatteryCapacityDebugInfo(context: Context): String {
        val builder = StringBuilder()
        builder.appendLine("=== 배터리 용량 감지 디버그 ===\n")

        // dumpsys battery
        builder.appendLine("1. dumpsys battery:")
        try {
            val process = Runtime.getRuntime().exec("dumpsys battery")
            val output = process.inputStream.bufferedReader().readText()
            builder.appendLine(output.take(500)) // 처음 500자만
        } catch (e: Exception) {
            builder.appendLine("실패: ${e.message}")
        }
        builder.appendLine()

        // 시스템 프로퍼티
        builder.appendLine("2. 시스템 프로퍼티:")
        val properties = listOf(
            "ro.config.battery_capacity",
            "persist.sys.battery.capacity",
            "ro.bat.capacity"
        )
        properties.forEach { prop ->
            try {
                val process = Runtime.getRuntime().exec("getprop $prop")
                val value = process.inputStream.bufferedReader().readText().trim()
                builder.appendLine("  $prop = $value")
            } catch (e: Exception) {
                builder.appendLine("  $prop = 실패")
            }
        }
        builder.appendLine()

        // 커널 파일
        builder.appendLine("3. 커널 파일:")
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design"
        )
        paths.forEach { path ->
            val file = File(path)
            builder.appendLine("  $path")
            builder.appendLine("    exists: ${file.exists()}")
            builder.appendLine("    canRead: ${file.canRead()}")
            if (file.exists() && file.canRead()) {
                try {
                    val content = file.readText().trim()
                    builder.appendLine("    value: $content")
                } catch (e: Exception) {
                    builder.appendLine("    read failed: ${e.message}")
                }
            }
        }
        builder.appendLine()

        // PowerProfile
        builder.appendLine("4. PowerProfile:")
        try {
            val capacity = getBatteryCapacityFromPowerProfile(context)
            builder.appendLine("  value: $capacity mAh")
        } catch (e: Exception) {
            builder.appendLine("  실패: ${e.message}")
        }
        builder.appendLine()

        // CHARGE_COUNTER 기반 추정
        builder.appendLine("5. CHARGE_COUNTER 추정:")
        try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val chargeCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
            val batteryInfo = getCurrentBatteryInfo(context)

            builder.appendLine("  chargeCounter: $chargeCounter µAh")
            builder.appendLine("  percentage: ${batteryInfo.percentage}%")

            if (batteryInfo.percentage in 20..95) {
                val estimated = ((chargeCounter / 1000.0) / batteryInfo.percentage * 100).toInt()
                builder.appendLine("  estimated capacity: $estimated mAh")
            } else {
                builder.appendLine("  percentage out of reliable range (20-95%)")
            }
        } catch (e: Exception) {
            builder.appendLine("  실패: ${e.message}")
        }

        return builder.toString()
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