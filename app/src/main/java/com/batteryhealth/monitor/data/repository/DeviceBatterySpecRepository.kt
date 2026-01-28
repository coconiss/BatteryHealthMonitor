// data/repository/DeviceBatterySpecRepository.kt
package com.batteryhealth.monitor.data.repository

import android.content.Context
import android.os.Build
import com.batteryhealth.monitor.data.local.dao.DeviceBatterySpecDao
import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec
import com.batteryhealth.monitor.data.remote.BatterySpecService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceBatterySpecRepository @Inject constructor(
    private val context: Context,
    private val specDao: DeviceBatterySpecDao,
    private val batterySpecService: BatterySpecService
) {

    /**
     * 우선순위에 따라 배터리 스펙 자동 탐지
     * 1. 로컬 DB 캐시
     * 2. 앱 내장 JSON 데이터베이스
     * 3. 온라인 API (GSMArena 스크래핑)
     * 4. 크라우드소싱 데이터베이스
     * 5. 기기 시스템 정보 추출 시도
     * 6. 추정값 (최후 수단)
     */
    suspend fun getDeviceSpec(): DeviceBatterySpec = withContext(Dispatchers.IO) {
        val deviceModel = Build.MODEL
        val manufacturer = Build.MANUFACTURER
        val deviceFingerprint = createDeviceFingerprint()

        Timber.d("Detecting battery spec for: $manufacturer $deviceModel")

        // 1. 로컬 DB 캐시 확인
        specDao.getSpec(deviceModel)?.let {
            Timber.d("Found spec in local DB cache")
            return@withContext it
        }

        // 2. 앱 내장 JSON 데이터베이스
        loadFromEmbeddedDatabase(deviceModel, manufacturer)?.let {
            Timber.d("Found spec in embedded database")
            specDao.insert(it)
            return@withContext it
        }

        // 3. 온라인 API 조회
        try {
            fetchFromOnlineApi(deviceModel, manufacturer)?.let {
                Timber.d("Found spec from online API")
                specDao.insert(it)
                return@withContext it
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch from online API")
        }

        // 4. 크라우드소싱 DB 조회
        try {
            fetchFromCrowdsourcedDatabase(deviceFingerprint)?.let {
                Timber.d("Found spec from crowdsourced database")
                specDao.insert(it)
                return@withContext it
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch from crowdsourced DB")
        }

        // 5. 시스템 정보에서 추출 시도
        extractFromSystemInfo()?.let {
            Timber.d("Extracted spec from system info")
            specDao.insert(it)
            return@withContext it
        }

        // 6. 추정값 생성 (최후 수단)
        Timber.w("Using estimated battery capacity")
        val estimatedSpec = createEstimatedSpec(deviceModel, manufacturer)
        specDao.insert(estimatedSpec)
        return@withContext estimatedSpec
    }

    /**
     * 앱에 내장된 JSON 데이터베이스에서 로드
     * assets/device_battery_specs.json
     */
    private fun loadFromEmbeddedDatabase(
        deviceModel: String,
        manufacturer: String
    ): DeviceBatterySpec? {
        return try {
            val json = context.assets.open("device_battery_specs.json")
                .bufferedReader()
                .use { it.readText() }

            val jsonObject = JSONObject(json)
            val devices = jsonObject.getJSONArray("devices")

            // 정확한 모델명 매칭
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val models = device.getJSONArray("models")

                for (j in 0 until models.length()) {
                    if (deviceModel.contains(models.getString(j), ignoreCase = true)) {
                        return DeviceBatterySpec(
                            deviceModel = deviceModel,
                            manufacturer = manufacturer,
                            designCapacity = device.getInt("capacity"),
                            source = "embedded_database",
                            confidence = 0.95f,
                            deviceName = device.optString("name", ""),
                            verified = true
                        )
                    }
                }
            }

            null
        } catch (e: Exception) {
            Timber.e(e, "Failed to load from embedded database")
            null
        }
    }

    /**
     * 온라인 API에서 배터리 스펙 조회
     * GSMArena, DeviceSpecifications.com 등
     */
    private suspend fun fetchFromOnlineApi(
        deviceModel: String,
        manufacturer: String
    ): DeviceBatterySpec? {
        return try {
            val response = batterySpecService.searchDevice(
                query = "$manufacturer $deviceModel"
            )

            response.firstOrNull()?.let { spec ->
                DeviceBatterySpec(
                    deviceModel = deviceModel,
                    manufacturer = manufacturer,
                    designCapacity = spec.batteryCapacity,
                    source = "online_api:${spec.source}",
                    confidence = 0.90f,
                    deviceName = spec.deviceName,
                    verified = spec.verified
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch from online API")
            null
        }
    }

    /**
     * 크라우드소싱 데이터베이스 조회
     * 다른 사용자들이 측정한 실제 배터리 용량 데이터
     */
    private suspend fun fetchFromCrowdsourcedDatabase(
        deviceFingerprint: String
    ): DeviceBatterySpec? {
        return try {
            val response = batterySpecService.getCrowdsourcedData(deviceFingerprint)

            if (response.sampleCount >= 10) { // 최소 10개 샘플
                DeviceBatterySpec(
                    deviceModel = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    designCapacity = response.averageCapacity,
                    source = "crowdsourced:${response.sampleCount}",
                    confidence = calculateCrowdsourcedConfidence(response.sampleCount),
                    deviceName = response.deviceName ?: "",
                    verified = false
                )
            } else {
                null
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch from crowdsourced database")
            null
        }
    }

    /**
     * 시스템 정보에서 배터리 용량 추출 시도
     * 일부 제조사(삼성, LG 등)는 시스템 프로퍼티에 배터리 정보 노출
     */
    private fun extractFromSystemInfo(): DeviceBatterySpec? {
        return try {
            // 시스템 프로퍼티 확인
            val capacity = tryGetSystemProperty("ro.config.battery_capacity")
                ?: tryGetSystemProperty("persist.sys.battery.capacity")
                ?: tryGetBatteryCapacityFromKernel()

            capacity?.let {
                DeviceBatterySpec(
                    deviceModel = Build.MODEL,
                    manufacturer = Build.MANUFACTURER,
                    designCapacity = it,
                    source = "system_property",
                    confidence = 0.85f,
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    verified = false
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to extract from system info")
            null
        }
    }

    /**
     * 시스템 프로퍼티 읽기
     */
    private fun tryGetSystemProperty(key: String): Int? {
        return try {
            val process = Runtime.getRuntime().exec("getprop $key")
            val value = process.inputStream.bufferedReader().use { it.readText() }.trim()
            process.waitFor()
            value.toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 커널 정보에서 배터리 용량 읽기 시도
     * /sys/class/power_supply/battery/charge_full_design
     */
    private fun tryGetBatteryCapacityFromKernel(): Int? {
        val paths = listOf(
            "/sys/class/power_supply/battery/charge_full_design",
            "/sys/class/power_supply/battery/batt_full_design",
            "/sys/class/power_supply/bms/charge_full_design"
        )

        for (path in paths) {
            try {
                val file = java.io.File(path)
                if (file.exists() && file.canRead()) {
                    val value = file.readText().trim().toLongOrNull()
                    // µAh -> mAh 변환
                    value?.let {
                        return (it / 1000).toInt()
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return null
    }

    /**
     * 추정값 생성 (화면 크기, 해상도 등 기반)
     */
    private fun createEstimatedSpec(
        deviceModel: String,
        manufacturer: String
    ): DeviceBatterySpec {
        // 화면 크기와 출시 연도 기반 추정
        val displayMetrics = context.resources.displayMetrics
        val screenInches = kotlin.math.sqrt(
            (displayMetrics.widthPixels / displayMetrics.xdpi).toDouble().pow(2) +
                    (displayMetrics.heightPixels / displayMetrics.ydpi).toDouble().pow(2)
        )

        val estimatedCapacity = when {
            screenInches < 5.5 -> 3000  // 소형 폰
            screenInches < 6.5 -> 4000  // 중형 폰
            screenInches < 7.0 -> 4500  // 대형 폰
            else -> 5000                 // 태블릿/폴더블
        }

        return DeviceBatterySpec(
            deviceModel = deviceModel,
            manufacturer = manufacturer,
            designCapacity = estimatedCapacity,
            source = "estimated_by_screen_size",
            confidence = 0.30f,
            deviceName = "$manufacturer $deviceModel",
            verified = false
        )
    }

    /**
     * 기기 고유 식별자 생성 (익명화)
     */
    private fun createDeviceFingerprint(): String {
        val fingerprint = "${Build.MANUFACTURER}-${Build.MODEL}-${Build.DEVICE}"
        return fingerprint.replace(" ", "_").lowercase()
    }

    private fun calculateCrowdsourcedConfidence(sampleCount: Int): Float {
        return when {
            sampleCount >= 100 -> 0.85f
            sampleCount >= 50 -> 0.75f
            sampleCount >= 20 -> 0.65f
            else -> 0.50f
        }
    }
}