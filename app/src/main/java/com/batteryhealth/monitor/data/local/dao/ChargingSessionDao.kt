// data/local/dao/ChargingSessionDao.kt
package com.batteryhealth.monitor.data.local.dao

import androidx.room.*
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface ChargingSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: ChargingSession): Long

    @Update
    suspend fun update(session: ChargingSession)

    @Delete
    suspend fun delete(session: ChargingSession)

    @Query("SELECT * FROM charging_sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): ChargingSession?

    @Query("SELECT * FROM charging_sessions WHERE isValid = 1 AND estimatedCapacity IS NOT NULL ORDER BY startTimestamp DESC")
    suspend fun getValidSessions(): List<ChargingSession>

    @Query("SELECT * FROM charging_sessions ORDER BY startTimestamp DESC")
    fun getAllSessionsFlow(): Flow<List<ChargingSession>>

    @Query("SELECT * FROM charging_sessions ORDER BY startTimestamp DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int): List<ChargingSession>

    @Query("SELECT COUNT(*) FROM charging_sessions")
    suspend fun getTotalSessionsCount(): Int

    @Query("SELECT COUNT(*) FROM charging_sessions WHERE isValid = 1")
    suspend fun getValidSessionsCount(): Int

    @Query("DELETE FROM charging_sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM charging_sessions WHERE endTimestamp IS NULL ORDER BY startTimestamp DESC LIMIT 1")
    suspend fun getOngoingSession(): ChargingSession?
}

