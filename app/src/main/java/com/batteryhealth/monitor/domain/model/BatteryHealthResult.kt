// domain/model/BatteryHealthResult.kt
package com.batteryhealth.monitor.domain.model

data class BatteryHealthResult(
    val healthPercentage: Float,
    val estimatedCurrentCapacity: Int, // mAh
    val designCapacity: Int, // mAh
    val confidenceLevel: ConfidenceLevel,
    val validSessionsCount: Int,
    val totalSessionsCount: Int,
    val lastUpdated: Long,
    val deviceSpecSource: String,
    val deviceSpecConfidence: Float
)