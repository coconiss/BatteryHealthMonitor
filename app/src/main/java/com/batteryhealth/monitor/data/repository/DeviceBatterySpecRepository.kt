package com.batteryhealth.monitor.data.repository

import android.content.Context
import android.os.Build
import com.batteryhealth.monitor.data.local.dao.DeviceBatterySpecDao
import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec
import com.batteryhealth.monitor.data.remote.BatterySpecService
import com.batteryhealth.monitor.util.BatteryUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow

@Singleton
class DeviceBatterySpecRepository @Inject constructor(
    private val context: Context,
    private val specDao: DeviceBatterySpecDao,
    private val batterySpecService: BatterySpecService
) {

    /**
     * 우선순위에 따라 배터리 스펙 자동 탐지
     * 1. 로컬 DB 캐시
     * 2. PowerProfile에서 직접 추출 (가장 정확!)
     * 3. 앱 내장 JSON 데이터베이스
     * 4. 시스템 정보에서 추출 시도
     * 5. 온라인 API (GSMArena 스크래핑)
     * 6. 크라우드소싱 데이터베이스
     * 7. 추정값 (최후 수단)
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

        // 2. PowerProfile에서 직접 배터리 용량 가져오기 (최우선!)
        BatteryUtils.getBatteryCapacity(context)?.let { capacity ->
            Timber.i("Successfully got battery capacity from system: $capacity mAh")
            val spec = DeviceBatterySpec(
                deviceModel = deviceModel,
                manufacturer = manufacturer,
                designCapacity = capacity,
                source = "power_profile",
                confidence = 1.0f, // 시스템에서 직접 가져온 값이므로 신뢰도 최상
                deviceName = "$manufacturer $deviceModel",
                verified = true
            )
            specDao.insert(spec)
            return@withContext spec
        }

        // 3. 앱 내장 JSON 데이터베이스
        loadFromEmbeddedDatabase(deviceModel, manufacturer)?.let {
            Timber.d("Found spec in embedded database")
            specDao.insert(it)
            return@withContext it
        }

        // 4. 온라인 API 조회
        try {
            fetchFromOnlineApi(deviceModel, manufacturer)?.let {
                Timber.d("Found spec from online API")
                specDao.insert(it)
                return@withContext it
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch from online API")
        }

        // 5. 크라우드소싱 DB 조회
        try {
            fetchFromCrowdsourcedDatabase(deviceFingerprint)?.let {
                Timber.d("Found spec from crowdsourced database")
                specDao.insert(it)
                return@withContext it
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to fetch from crowdsourced DB")
        }

        // 6. 추정값 생성 (최후 수단)
        Timber.w("Using estimated battery capacity")
        val estimatedSpec = createEstimatedSpec(deviceModel, manufacturer)
        specDao.insert(estimatedSpec)
        return@withContext estimatedSpec
    }

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

    private suspend fun fetchFromCrowdsourcedDatabase(
        deviceFingerprint: String
    ): DeviceBatterySpec? {
        return try {
            val response = batterySpecService.getCrowdsourcedData(deviceFingerprint)

            if (response.sampleCount >= 10) {
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

    private fun createEstimatedSpec(
        deviceModel: String,
        manufacturer: String
    ): DeviceBatterySpec {
        val displayMetrics = context.resources.displayMetrics
        val screenInches = kotlin.math.sqrt(
            (displayMetrics.widthPixels / displayMetrics.xdpi).toDouble().pow(2) +
                    (displayMetrics.heightPixels / displayMetrics.ydpi).toDouble().pow(2)
        )

        val estimatedCapacity = when {
            screenInches < 5.5 -> 3000
            screenInches < 6.5 -> 4000
            screenInches < 7.0 -> 4500
            else -> 5000
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