// domain/calculator/BatteryHealthCalculator.kt (개선 버전)
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

        // 충전 데이터와 방전 데이터 분리
        val chargingSessions = validSessions.filter { it.chargerType != "DISCHARGE" }
        val dischargingSessions = validSessions.filter { it.chargerType == "DISCHARGE" }

        Timber.d("Charging sessions: ${chargingSessions.size}, Discharging sessions: ${dischargingSessions.size}")

        // 충전 데이터 분석
        val chargingCapacities = chargingSessions.mapNotNull { it.estimatedCapacity }
        val filteredChargingCapacities = if (chargingCapacities.isNotEmpty()) {
            removeOutliers(chargingCapacities)
        } else {
            emptyList()
        }

        // 방전 데이터 분석
        val dischargingCapacities = dischargingSessions.mapNotNull { it.estimatedCapacity }
        val filteredDischargingCapacities = if (dischargingCapacities.isNotEmpty()) {
            removeOutliers(dischargingCapacities)
        } else {
            emptyList()
        }

        // 데이터 병합 (충전 데이터에 더 높은 가중치)
        val weightedCapacities = mutableListOf<Int>()

        // 충전 데이터: 가중치 2 (더 신뢰성 높음)
        filteredChargingCapacities.forEach { capacity ->
            weightedCapacities.add(capacity)
            weightedCapacities.add(capacity)
        }

        // 방전 데이터: 가중치 1
        weightedCapacities.addAll(filteredDischargingCapacities)

        if (weightedCapacities.isEmpty()) {
            Timber.w("All capacities were outliers or no valid data")
            return null
        }

        // 평균 추정 용량
        val averageEstimatedCapacity = weightedCapacities.average().toInt()

        // Health % 계산
        val healthPercentage = (averageEstimatedCapacity.toFloat() /
                deviceSpec.designCapacity) * 100

        // 100% 초과 방지 (센서 오차)
        val adjustedHealth = healthPercentage.coerceIn(0f, 105f)

        // 신뢰도 평가 (충전+방전 데이터 모두 고려)
        val totalValidSamples = filteredChargingCapacities.size + filteredDischargingCapacities.size
        val confidence = evaluateConfidence(
            validSessionsCount = totalValidSamples,
            deviceSpecConfidence = deviceSpec.confidence,
            hasDischargeData = dischargingSessions.isNotEmpty()
        )

        Timber.d("Battery health: ${adjustedHealth}%, confidence: $confidence")
        Timber.d("Based on ${filteredChargingCapacities.size} charging + ${filteredDischargingCapacities.size} discharging sessions")

        return BatteryHealthResult(
            healthPercentage = adjustedHealth,
            estimatedCurrentCapacity = averageEstimatedCapacity,
            designCapacity = deviceSpec.designCapacity,
            confidenceLevel = confidence,
            validSessionsCount = totalValidSamples,
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
     * 신뢰도 평가 (방전 데이터 고려)
     */
    private fun evaluateConfidence(
        validSessionsCount: Int,
        deviceSpecConfidence: Float,
        hasDischargeData: Boolean
    ): ConfidenceLevel {
        // 기기 스펙 신뢰도가 낮으면 전체 신뢰도 하락
        if (deviceSpecConfidence < 0.5f) {
            return ConfidenceLevel.VERY_LOW
        }

        // 방전 데이터가 있으면 신뢰도 +1 레벨 상승
        val adjustedCount = if (hasDischargeData) {
            validSessionsCount + 2 // 방전 데이터 보너스
        } else {
            validSessionsCount
        }

        return when (adjustedCount) {
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

        val percentageChange = abs(endPercentage - startPercentage)
        if (percentageChange < 5) {
            Timber.w("Insufficient change: ${percentageChange}%")
            return null
        }

        val chargedMicroAh = abs(endCounter - startCounter)
        if (chargedMicroAh <= 0) {
            Timber.w("Invalid charge counter delta: $chargedMicroAh")
            return null
        }

        val chargedMah = chargedMicroAh / 1000.0
        val estimatedCapacity = chargedMah / (percentageChange / 100.0)

        Timber.d("Estimated capacity: ${estimatedCapacity.toInt()} mAh " +
                "(changed: ${chargedMah.toInt()} mAh, delta: ${percentageChange}%)")

        // 비현실적인 값 필터링
        if (estimatedCapacity < 500 || estimatedCapacity > 20000) {
            Timber.w("Unrealistic capacity estimate: ${estimatedCapacity.toInt()} mAh")
            return null
        }

        return estimatedCapacity.toInt()
    }
}