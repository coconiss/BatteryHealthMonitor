// data/local/entity/BatteryMeasurement.kt
package com.batteryhealth.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "battery_measurements",
    foreignKeys = [
        ForeignKey(
            entity = ChargingSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class BatteryMeasurement(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,
    val timestamp: Long,

    val chargeCounter: Long?, // µAh
    val temperature: Float,   // °C
    val voltage: Int,         // mV
    val percentage: Int,      // 0-100
    val current: Int? = null  // µA
)