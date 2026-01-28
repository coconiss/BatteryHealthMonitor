// data/local/dao/DeviceBatterySpecDao.kt
package com.batteryhealth.monitor.data.local.dao

import androidx.room.*
import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec

@Dao
interface DeviceBatterySpecDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(spec: DeviceBatterySpec)

    @Query("SELECT * FROM device_battery_specs WHERE deviceModel = :deviceModel")
    suspend fun getSpec(deviceModel: String): DeviceBatterySpec?

    @Query("DELETE FROM device_battery_specs WHERE deviceModel = :deviceModel")
    suspend fun deleteSpec(deviceModel: String)

    @Query("SELECT * FROM device_battery_specs")
    suspend fun getAllSpecs(): List<DeviceBatterySpec>
}

