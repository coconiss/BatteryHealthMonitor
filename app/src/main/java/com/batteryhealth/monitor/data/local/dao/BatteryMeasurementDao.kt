// data/local/dao/BatteryMeasurementDao.kt
package com.batteryhealth.monitor.data.local.dao

import androidx.room.*
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement

@Dao
interface BatteryMeasurementDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(measurement: BatteryMeasurement): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(measurements: List<BatteryMeasurement>)

    @Query("SELECT * FROM battery_measurements WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMeasurementsBySession(sessionId: Long): List<BatteryMeasurement>

    @Query("DELETE FROM battery_measurements WHERE sessionId = :sessionId")
    suspend fun deleteMeasurementsBySession(sessionId: Long)

    @Query("DELETE FROM battery_measurements")
    suspend fun deleteAll()
}