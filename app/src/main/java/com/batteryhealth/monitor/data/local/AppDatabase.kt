// data/local/AppDatabase.kt
package com.batteryhealth.monitor.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.batteryhealth.monitor.data.local.dao.BatteryMeasurementDao
import com.batteryhealth.monitor.data.local.dao.ChargingSessionDao
import com.batteryhealth.monitor.data.local.dao.DeviceBatterySpecDao
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec

@Database(
    entities = [
        ChargingSession::class,
        DeviceBatterySpec::class,
        BatteryMeasurement::class
    ],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chargingSessionDao(): ChargingSessionDao
    abstract fun deviceBatterySpecDao(): DeviceBatterySpecDao
    abstract fun batteryMeasurementDao(): BatteryMeasurementDao
}