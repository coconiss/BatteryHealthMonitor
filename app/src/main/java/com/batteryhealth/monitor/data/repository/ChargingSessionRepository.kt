// data/repository/ChargingSessionRepository.kt
package com.batteryhealth.monitor.data.repository

import com.batteryhealth.monitor.data.local.dao.BatteryMeasurementDao
import com.batteryhealth.monitor.data.local.dao.ChargingSessionDao
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChargingSessionRepository @Inject constructor(
    private val sessionDao: ChargingSessionDao,
    private val measurementDao: BatteryMeasurementDao
) {

    suspend fun insertSession(session: ChargingSession): Long {
        return sessionDao.insert(session)
    }

    suspend fun updateSession(session: ChargingSession) {
        sessionDao.update(session)
    }

    suspend fun getSessionById(id: Long): ChargingSession? {
        return sessionDao.getSessionById(id)
    }

    suspend fun getValidSessions(): List<ChargingSession> {
        return sessionDao.getValidSessions()
    }

    fun getAllSessionsFlow(): Flow<List<ChargingSession>> {
        return sessionDao.getAllSessionsFlow()
    }

    suspend fun getRecentSessions(limit: Int = 10): List<ChargingSession> {
        return sessionDao.getRecentSessions(limit)
    }

    suspend fun getTotalSessionsCount(): Int {
        return sessionDao.getTotalSessionsCount()
    }

    suspend fun getValidSessionsCount(): Int {
        return sessionDao.getValidSessionsCount()
    }

    suspend fun insertMeasurements(measurements: List<BatteryMeasurement>) {
        measurementDao.insertAll(measurements)
    }

    suspend fun getMeasurementsBySession(sessionId: Long): List<BatteryMeasurement> {
        return measurementDao.getMeasurementsBySession(sessionId)
    }

    suspend fun deleteAllSessions() {
        sessionDao.deleteAll()
        measurementDao.deleteAll()
    }

    suspend fun getOngoingSession(): ChargingSession? {
        return sessionDao.getOngoingSession()
    }
}