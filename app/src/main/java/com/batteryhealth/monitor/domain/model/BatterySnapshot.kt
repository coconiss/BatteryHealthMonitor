// domain/model/BatterySnapshot.kt
package com.batteryhealth.monitor.domain.model

/**
 * 특정 시점의 배터리 상태 스냅샷
 * 방전 중 배터리 건강도 추정에 사용
 */
data class BatterySnapshot(
    val timestamp: Long,
    val percentage: Int,
    val chargeCounter: Long?, // µAh
    val temperature: Float,   // °C
    val voltage: Int,         // mV
    val current: Int?         // µA
) {
    /**
     * 두 스냅샷 간의 시간 차이 (초)
     */
    fun timeDeltaSeconds(other: BatterySnapshot): Long {
        return Math.abs(timestamp - other.timestamp) / 1000
    }

    /**
     * 두 스냅샷 간의 배터리 퍼센트 변화
     */
    fun percentageDelta(other: BatterySnapshot): Int {
        return Math.abs(percentage - other.percentage)
    }

    /**
     * 두 스냅샷 간의 충전량 변화 (mAh)
     */
    fun chargeCounterDeltaMah(other: BatterySnapshot): Int? {
        if (chargeCounter == null || other.chargeCounter == null) return null
        return (Math.abs(chargeCounter - other.chargeCounter) / 1000).toInt()
    }
}