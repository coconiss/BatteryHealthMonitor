// di/RepositoryModule.kt
package com.batteryhealth.monitor.di

import android.content.Context
import com.batteryhealth.monitor.data.local.dao.BatteryMeasurementDao
import com.batteryhealth.monitor.data.local.dao.ChargingSessionDao
import com.batteryhealth.monitor.data.local.dao.DeviceBatterySpecDao
import com.batteryhealth.monitor.data.remote.BatterySpecApi
import com.batteryhealth.monitor.data.remote.BatterySpecService
import com.batteryhealth.monitor.data.remote.GsmArenaScraperService
import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import com.batteryhealth.monitor.data.repository.DeviceBatterySpecRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RepositoryModule {

    /**
     * ChargingSessionRepository 제공
     */
    @Provides
    @Singleton
    fun provideChargingSessionRepository(
        sessionDao: ChargingSessionDao,
        measurementDao: BatteryMeasurementDao
    ): ChargingSessionRepository {
        return ChargingSessionRepository(
            sessionDao = sessionDao,
            measurementDao = measurementDao
        )
    }

    /**
     * DeviceBatterySpecRepository 제공
     */
    @Provides
    @Singleton
    fun provideDeviceBatterySpecRepository(
        @ApplicationContext context: Context,
        specDao: DeviceBatterySpecDao,
        batterySpecService: BatterySpecService
    ): DeviceBatterySpecRepository {
        return DeviceBatterySpecRepository(
            context = context,
            specDao = specDao,
            batterySpecService = batterySpecService
        )
    }

    /**
     * BatterySpecService 제공 (API 래퍼)
     */
    @Provides
    @Singleton
    fun provideBatterySpecService(
        batterySpecApi: BatterySpecApi,
        gsmArenaScraperService: GsmArenaScraperService
    ): BatterySpecService {
        return BatterySpecServiceImpl(
            api = batterySpecApi,
            scraperService = gsmArenaScraperService
        )
    }
}

/**
 * BatterySpecService 구현체
 * BatterySpecApi와 GsmArenaScraperService를 조합하여 사용
 */
private class BatterySpecServiceImpl(
    private val api: BatterySpecApi,
    private val scraperService: GsmArenaScraperService
) : BatterySpecService {

    override suspend fun searchDevice(query: String): List<com.batteryhealth.monitor.data.remote.dto.DeviceSpecResponse> {
        return try {
            // 1. 먼저 API 시도
            api.searchDevice(query)
        } catch (e: Exception) {
            // 2. API 실패 시 웹 스크래핑 시도
            timber.log.Timber.w(e, "API search failed, trying web scraping")

            // query를 제조사와 모델로 분리
            val parts = query.split(" ", limit = 2)
            if (parts.size >= 2) {
                val manufacturer = parts[0]
                val model = parts[1]

                val capacity = scraperService.searchBatteryCapacity(manufacturer, model)

                if (capacity != null) {
                    listOf(
                        com.batteryhealth.monitor.data.remote.dto.DeviceSpecResponse(
                            deviceName = query,
                            batteryCapacity = capacity,
                            source = "gsmarena_scraping",
                            verified = false,
                            confidence = 0.75f
                        )
                    )
                } else {
                    emptyList()
                }
            } else {
                emptyList()
            }
        }
    }

    override suspend fun getCrowdsourcedData(
        deviceFingerprint: String
    ): com.batteryhealth.monitor.data.remote.dto.CrowdsourcedDataResponse {
        return api.getCrowdsourcedData(deviceFingerprint)
    }

    override suspend fun submitCrowdsourcedData(
        data: com.batteryhealth.monitor.data.remote.dto.CrowdsourcedSubmission
    ): com.batteryhealth.monitor.data.remote.dto.SubmitResponse {
        return api.submitCrowdsourcedData(data)
    }
}