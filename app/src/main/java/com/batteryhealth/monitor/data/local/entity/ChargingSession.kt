// data/local/entity/ChargingSession.kt
package com.batteryhealth.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "charging_sessions")
data class ChargingSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startTimestamp: Long,
    val endTimestamp: Long? = null,

    val startPercentage: Int,
    val endPercentage: Int? = null,

    val startChargeCounter: Long? = null, // µAh
    val endChargeCounter: Long? = null,   // µAh

    val averageTemperature: Float,
    val maxTemperature: Float = 0f,
    val averageVoltage: Int,

    val estimatedCapacity: Int? = null, // mAh
    val isValid: Boolean = true,
    val invalidReason: String? = null,

    val chargerType: String? = null, // "AC", "USB", "Wireless"
    val chargingSpeed: String? = null // "Fast", "Normal", "Slow"
)
