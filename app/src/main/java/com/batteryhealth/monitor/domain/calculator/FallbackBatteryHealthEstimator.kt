package com.batteryhealth.monitor.domain.calculator

// FallbackBatteryHealthEstimator.kt
class FallbackBatteryHealthEstimator(
    private val context: Context,
    private val sessionDao: ChargingSessionDao
) {

    /**
     * CHARGE_COUNTER 미지원 시 전압-퍼센트 곡선 분석 방식
     * (정확도 낮음, 참고용으로만 사용)
     */
    suspend fun estimateHealthByVoltageCurve(): BatteryHealthResult? {
        val sessions = sessionDao.getAllSessions()

        if (sessions.size < 5) {
            return null // 최소 5회 충전 데이터 필요
        }

        // 충전 초기(20-30%) 구간의 전압 상승 속도 분석
        val earlyChargeVoltageRates = sessions.mapNotNull { session ->
            analyzeEarlyChargeVoltageRate(session)
        }

        if (earlyChargeVoltageRates.isEmpty()) {
            return null
        }

        // 배터리 노화 시 전압 상승 속도가 빨라지는 경향 활용
        val averageRate = earlyChargeVoltageRates.average()
        val baselineRate = 0.05 // 신품 기준 전압 상승률 (가정)

        // 매우 대략적인 추정
        val estimatedHealth = (baselineRate / averageRate * 100)
            .coerceIn(50f, 100f) // 50-100% 범위로 제한

        return BatteryHealthResult(
            healthPercentage = estimatedHealth,
            estimatedCurrentCapacity = 0, // 알 수 없음
            designCapacity = 0,
            confidenceLevel = ConfidenceLevel.VERY_LOW,
            validSessionsCount = earlyChargeVoltageRates.size,
            totalSessionsCount = sessions.size,
            lastUpdated = System.currentTimeMillis()
        )
    }

    private fun analyzeEarlyChargeVoltageRate(session: ChargingSession): Float? {
        // 실제 구현 필요: 20-30% 구간 전압 변화율 계산
        return null
    }

    /**
     * 충전 시간 기반 추정 (가장 낮은 정확도)
     */
    fun estimateByChargingTime(): BatteryHealthResult? {
        // 동일 조건(충전기, 온도)에서 충전 시간이 늘어나면 배터리 노화 의심
        // 정확도가 매우 낮아 권장하지 않음
        return null
    }
}