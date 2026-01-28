// domain/calculator/BatteryHealthCalculator.kt
package com.batteryhealth.monitor.domain.calculator

import com.batteryhealth.monitor.data.local.dao.ChargingSessionDao
import com.batteryhealth.monitor.data.repository.DeviceBatterySpecRepository
import com.batteryhealth.monitor.domain.model.BatteryHealthResult
import com.batteryhealth.monitor.domain.model.ConfidenceLevel
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

class BatteryHealthCalculator @Inject constructor(
    private val sessionDao: ChargingSessionDao,
    private val deviceSpecRepository: DeviceBatterySpecRepository
) {

    suspend fun calculateBatteryHealth(): BatteryHealthResult? {
        val validSessions = sessionDao.getValidSessions()
        val totalSessions = sessionDao.getTotalSessionsCount()

        Timber.d("Calculating battery health with ${validSessions.size} valid sessions")

        if (validSessions.isEmpty()) {
            Timber.w("No valid sessions available")
            return null
        }

        val deviceSpec = deviceSpecRepository.getDeviceSpec()

        val capacities = validSessions.mapNotNull { it.estimatedCapacity }
        if (capacities.isEmpty()) {
            Timber.w("No capacity estimates available")
            return null
        }

        // 아웃라이어 제거
        val filteredCapacities = removeOutliers(capacities)
        if (filteredCapacities.isEmpty()) {
            Timber.w("All capacities were outliers")
            return null
        }

        // 평균 추정 용량
        val averageEstimatedCapacity = filteredCapacities.average().toInt()

        // Health % 계산
        val healthPercentage = (averageEstimatedCapacity.toFloat() /
                deviceSpec.designCapacity) * 100

        // 100% 초과 방지 (센서 오차)
        val adjustedHealth = healthPercentage.coerceIn(0f, 105f)

        // 신뢰도 평가
        val confidence = evaluateConfidence(
            validSessionsCount = filteredCapacities.size,
            deviceSpecConfidence = deviceSpec.confidence
        )

        Timber.d("Battery health: ${adjustedHealth}%, confidence: $confidence")

        return BatteryHealthResult(
            healthPercentage = adjustedHealth,
            estimatedCurrentCapacity = averageEstimatedCapacity,
            designCapacity = deviceSpec.designCapacity,
            confidenceLevel = confidence,
            validSessionsCount = filteredCapacities.size,
            totalSessionsCount = totalSessions,
            lastUpdated = System.currentTimeMillis(),
            deviceSpecSource = deviceSpec.source,
            deviceSpecConfidence = deviceSpec.confidence
        )
    }

    /**
     * IQR 방식으로 아웃라이어 제거
     */
    private fun removeOutliers(values: List<Int>): List<Int> {
        if (values.size < 4) return values

        val sorted = values.sorted()
        val q1Index = sorted.size / 4
        val q3Index = (sorted.size * 3) / 4

        val q1 = sorted[q1Index].toDouble()
        val q3 = sorted[q3Index].toDouble()
        val iqr = q3 - q1

        val lowerBound = q1 - (1.5 * iqr)
        val upperBound = q3 + (1.5 * iqr)

        val filtered = values.filter { (it.toDouble()) in lowerBound..upperBound }

        Timber.d("Outlier removal: ${values.size} -> ${filtered.size} values")
        Timber.d("Bounds: [$lowerBound, $upperBound], IQR: $iqr")

        return filtered
    }

    /**
     * 신뢰도 평가
     */
    private fun evaluateConfidence(
        validSessionsCount: Int,
        deviceSpecConfidence: Float
    ): ConfidenceLevel {
        // 기기 스펙 신뢰도가 낮으면 전체 신뢰도 하락
        if (deviceSpecConfidence < 0.5f) {
            return ConfidenceLevel.VERY_LOW
        }

        return when (validSessionsCount) {
            1 -> ConfidenceLevel.VERY_LOW
            2 -> ConfidenceLevel.LOW
            in 3..4 -> ConfidenceLevel.MEDIUM
            in 5..9 -> ConfidenceLevel.HIGH
            else -> ConfidenceLevel.VERY_HIGH
        }
    }

    /**
     * 단일 세션의 추정 용량 계산
     */
    fun calculateEstimatedCapacity(
        startCounter: Long?,
        endCounter: Long?,
        startPercentage: Int,
        endPercentage: Int
    ): Int? {
        if (startCounter == null || endCounter == null) {
            Timber.w("Charge counter not available")
            return null
        }

        val percentageChange = endPercentage - startPercentage
        if (percentageChange < 10) {
            Timber.w("Insufficient charge: ${percentageChange}%")
            return null
        }

        val chargedMicroAh = endCounter - startCounter
        if (chargedMicroAh <= 0) {
            Timber.w("Invalid charge counter delta: $chargedMicroAh")
            return null
        }

        val chargedMah = chargedMicroAh / 1000.0
        val estimatedCapacity = chargedMah / (percentageChange / 100.0)

        Timber.d("Estimated capacity: ${estimatedCapacity.toInt()} mAh " +
                "(charged: ${chargedMah.toInt()} mAh, delta: ${percentageChange}%)")

        return estimatedCapacity.toInt()
    }
}