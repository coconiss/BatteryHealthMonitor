// service/BatteryMonitoringService.kt (개선 버전)
package com.batteryhealth.monitor.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.batteryhealth.monitor.R
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import com.batteryhealth.monitor.domain.calculator.BatteryHealthCalculator
import com.batteryhealth.monitor.domain.model.BatterySnapshot
import com.batteryhealth.monitor.ui.main.MainActivity
import com.batteryhealth.monitor.util.NotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class BatteryMonitoringService : Service() {

    @Inject
    lateinit var sessionRepository: ChargingSessionRepository

    @Inject
    lateinit var healthCalculator: BatteryHealthCalculator

    @Inject
    lateinit var notificationHelper: NotificationHelper

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val batteryManager by lazy {
        getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    }

    // 충전 모니터링 관련
    private var currentSessionId: Long? = null
    private val chargingMeasurements = mutableListOf<BatteryMeasurement>()
    private var sessionStartTime: Long = 0L
    private var lastPercentage: Int = 0

    // 방전 모니터링 관련
    private var isDischargeMonitoring = false
    private val dischargeMeasurements = mutableListOf<BatteryMeasurement>()
    private var lastDischargeSnapshot: BatterySnapshot? = null
    private var dischargeStartSnapshot: BatterySnapshot? = null

    private val handler = Handler(Looper.getMainLooper())

    // 충전 모니터링 (30초 간격)
    private val chargingMonitoringRunnable = object : Runnable {
        override fun run() {
            collectChargingData()
            handler.postDelayed(this, MONITORING_INTERVAL_MS)
        }
    }

    // 방전 모니터링 (5분 간격)
    private val dischargeMonitoringRunnable = object : Runnable {
        override fun run() {
            collectDischargeData()
            handler.postDelayed(this, DISCHARGE_MONITORING_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("BatteryMonitoringService created")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START_MONITORING -> startChargingMonitoring()
            ACTION_STOP_MONITORING -> stopChargingMonitoring()
            ACTION_START_DISCHARGE_MONITORING -> startDischargeMonitoring()
            ACTION_STOP_DISCHARGE_MONITORING -> stopDischargeMonitoring()
        }

        return START_STICKY
    }

    /**
     * 충전 모니터링 시작
     */
    private fun startChargingMonitoring() {
        Timber.i("Starting charging monitoring")

        if (currentSessionId != null) {
            Timber.w("Already monitoring charging session: $currentSessionId")
            return
        }

        startForeground(
            NotificationHelper.MONITORING_NOTIFICATION_ID,
            notificationHelper.createMonitoringNotification()
        )

        sessionStartTime = System.currentTimeMillis()
        val startPercentage = getBatteryPercentage()
        lastPercentage = startPercentage

        serviceScope.launch {
            try {
                val session = ChargingSession(
                    startTimestamp = sessionStartTime,
                    startPercentage = startPercentage,
                    startChargeCounter = getChargeCounter(),
                    averageTemperature = getTemperature(),
                    averageVoltage = getVoltage(),
                    chargerType = getChargerType()
                )

                currentSessionId = sessionRepository.insertSession(session)
                chargingMeasurements.clear()

                Timber.d("Created charging session: $currentSessionId (Start: $startPercentage%)")

                handler.post(chargingMonitoringRunnable)

            } catch (e: Exception) {
                Timber.e(e, "Failed to start charging monitoring")
                stopSelf()
            }
        }
    }

    /**
     * 충전 중 데이터 수집
     */
    private fun collectChargingData() {
        val sessionId = currentSessionId ?: return

        val chargeCounter = getChargeCounter()
        val temperature = getTemperature()
        val voltage = getVoltage()
        val percentage = getBatteryPercentage()
        val current = getCurrent()

        Timber.v("Charging data: $percentage%, ${temperature}°C, ${voltage}mV")

        lastPercentage = percentage

        if (temperature > MAX_SAFE_TEMPERATURE) {
            Timber.w("High temperature detected: ${temperature}°C")
            markSessionInvalid("고온: ${temperature}°C")
            return
        }

        val measurement = BatteryMeasurement(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            chargeCounter = chargeCounter,
            temperature = temperature,
            voltage = voltage,
            percentage = percentage,
            current = current
        )

        chargingMeasurements.add(measurement)

        if (chargingMeasurements.size >= 5) {
            serviceScope.launch {
                sessionRepository.insertMeasurements(chargingMeasurements.toList())
                Timber.d("Saved ${chargingMeasurements.size} measurements to DB")
                chargingMeasurements.clear()
            }
        }

        val isCurrentlyCharging = isCharging()
        if (!isCurrentlyCharging || percentage == 100) {
            Timber.i("Charging completed or stopped")
            finalizeChargingSession()
            return
        }

        updateNotification(percentage, temperature)
    }

    /**
     * 충전 세션 종료
     */
    private fun finalizeChargingSession() {
        val sessionId = currentSessionId ?: return

        Timber.i("Finalizing charging session: $sessionId")

        handler.removeCallbacks(chargingMonitoringRunnable)

        serviceScope.launch {
            try {
                if (chargingMeasurements.isNotEmpty()) {
                    sessionRepository.insertMeasurements(chargingMeasurements)
                    Timber.d("Saved remaining ${chargingMeasurements.size} measurements")
                }

                val session = sessionRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.e("Session not found: $sessionId")
                    currentSessionId = null
                    chargingMeasurements.clear()
                    stopSelf()
                    return@launch
                }

                val endChargeCounter = getChargeCounter()
                val endPercentage = getBatteryPercentage()
                val endTime = System.currentTimeMillis()

                val estimatedCapacity = healthCalculator.calculateEstimatedCapacity(
                    startCounter = session.startChargeCounter,
                    endCounter = endChargeCounter,
                    startPercentage = session.startPercentage,
                    endPercentage = endPercentage
                )

                val allMeasurements = sessionRepository.getMeasurementsBySession(sessionId)

                val avgTemp = if (allMeasurements.isNotEmpty()) {
                    allMeasurements.map { it.temperature }.average().toFloat()
                } else {
                    session.averageTemperature
                }

                val maxTemp = if (allMeasurements.isNotEmpty()) {
                    allMeasurements.maxOfOrNull { it.temperature } ?: session.averageTemperature
                } else {
                    session.averageTemperature
                }

                val avgVoltage = if (allMeasurements.isNotEmpty()) {
                    allMeasurements.map { it.voltage }.average().toInt()
                } else {
                    session.averageVoltage
                }

                val updatedSession = session.copy(
                    endTimestamp = endTime,
                    endPercentage = endPercentage,
                    endChargeCounter = endChargeCounter,
                    estimatedCapacity = estimatedCapacity,
                    averageTemperature = avgTemp,
                    maxTemperature = maxTemp,
                    averageVoltage = avgVoltage
                )

                sessionRepository.updateSession(updatedSession)

                Timber.i("Charging session finalized: $estimatedCapacity mAh")

                notificationHelper.showSessionCompletedNotification(
                    sessionId = sessionId,
                    estimatedCapacity = estimatedCapacity
                )

            } catch (e: Exception) {
                Timber.e(e, "Failed to finalize charging session")
            } finally {
                currentSessionId = null
                chargingMeasurements.clear()
                lastPercentage = 0
                stopSelf()
            }
        }
    }

    /**
     * 방전 모니터링 시작
     */
    private fun startDischargeMonitoring() {
        Timber.i("Starting discharge monitoring")

        if (isDischargeMonitoring) {
            Timber.w("Already monitoring discharge")
            return
        }

        // Foreground 알림 표시
        startForeground(
            NotificationHelper.DISCHARGE_MONITORING_NOTIFICATION_ID,
            notificationHelper.createDischargeMonitoringNotification()
        )

        isDischargeMonitoring = true
        dischargeMeasurements.clear()

        // 초기 스냅샷 생성
        dischargeStartSnapshot = createBatterySnapshot()
        lastDischargeSnapshot = dischargeStartSnapshot

        Timber.d("Discharge monitoring started at ${dischargeStartSnapshot?.percentage}%")

        handler.post(dischargeMonitoringRunnable)
    }

    /**
     * 방전 중 데이터 수집
     */
    private fun collectDischargeData() {
        if (!isDischargeMonitoring) return

        val currentSnapshot = createBatterySnapshot()

        lastDischargeSnapshot?.let { lastSnapshot ->
            val percentageDelta = lastSnapshot.percentageDelta(currentSnapshot)
            val timeDeltaMinutes = lastSnapshot.timeDeltaSeconds(currentSnapshot) / 60

            Timber.d("Discharge: -${percentageDelta}% in ${timeDeltaMinutes}min, " +
                    "Current: ${currentSnapshot.percentage}%, Temp: ${currentSnapshot.temperature}°C")

            // 배터리 건강도 추정에 사용할 데이터 저장
            if (percentageDelta >= 5) { // 최소 5% 변화 시 기록
                serviceScope.launch {
                    saveDischargeDataPoint(lastSnapshot, currentSnapshot)
                }
            }
        }

        lastDischargeSnapshot = currentSnapshot

        // 충전이 시작되면 방전 모니터링 중지
        if (isCharging()) {
            Timber.i("Charging started, stopping discharge monitoring")
            stopDischargeMonitoring()
        }

        // 배터리가 너무 낮으면 중지 (10% 이하)
        if (currentSnapshot.percentage <= 10) {
            Timber.w("Battery too low (${currentSnapshot.percentage}%), stopping discharge monitoring")
            stopDischargeMonitoring()
        }

        updateDischargeNotification(currentSnapshot.percentage, currentSnapshot.temperature)
    }

    /**
     * 방전 데이터 포인트 저장
     */
    private suspend fun saveDischargeDataPoint(
        startSnapshot: BatterySnapshot,
        endSnapshot: BatterySnapshot
    ) {
        try {
            // 방전 데이터를 별도 테이블에 저장하거나
            // ChargingSession의 isValid=false로 저장 후 나중에 분석
            val percentageDelta = startSnapshot.percentageDelta(endSnapshot)
            val chargeCounterDelta = startSnapshot.chargeCounterDeltaMah(endSnapshot)

            if (chargeCounterDelta != null && percentageDelta > 0) {
                val estimatedCapacity = (chargeCounterDelta * 100) / percentageDelta

                Timber.d("Discharge capacity estimate: $estimatedCapacity mAh " +
                        "(based on ${percentageDelta}% discharge)")

                // 방전 데이터를 특별한 세션으로 저장
                val dischargeSession = ChargingSession(
                    startTimestamp = startSnapshot.timestamp,
                    endTimestamp = endSnapshot.timestamp,
                    startPercentage = startSnapshot.percentage,
                    endPercentage = endSnapshot.percentage,
                    startChargeCounter = startSnapshot.chargeCounter,
                    endChargeCounter = endSnapshot.chargeCounter,
                    averageTemperature = (startSnapshot.temperature + endSnapshot.temperature) / 2,
                    maxTemperature = maxOf(startSnapshot.temperature, endSnapshot.temperature),
                    averageVoltage = (startSnapshot.voltage + endSnapshot.voltage) / 2,
                    estimatedCapacity = estimatedCapacity,
                    isValid = true,
                    chargerType = "DISCHARGE", // 방전 데이터 표시
                    chargingSpeed = null
                )

                sessionRepository.insertSession(dischargeSession)
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to save discharge data point")
        }
    }

    /**
     * 방전 모니터링 중지
     */
    private fun stopDischargeMonitoring() {
        Timber.i("Stopping discharge monitoring")

        handler.removeCallbacks(dischargeMonitoringRunnable)
        isDischargeMonitoring = false
        dischargeMeasurements.clear()
        lastDischargeSnapshot = null
        dischargeStartSnapshot = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 현재 배터리 상태 스냅샷 생성
     */
    private fun createBatterySnapshot(): BatterySnapshot {
        return BatterySnapshot(
            timestamp = System.currentTimeMillis(),
            percentage = getBatteryPercentage(),
            chargeCounter = getChargeCounter(),
            temperature = getTemperature(),
            voltage = getVoltage(),
            current = getCurrent()
        )
    }

    private fun stopChargingMonitoring() {
        Timber.i("Stopping charging monitoring")
        handler.removeCallbacks(chargingMonitoringRunnable)

        if (currentSessionId != null) {
            finalizeChargingSession()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun markSessionInvalid(reason: String) {
        val sessionId = currentSessionId ?: return

        serviceScope.launch {
            try {
                val session = sessionRepository.getSessionById(sessionId) ?: return@launch

                val invalidSession = session.copy(
                    isValid = false,
                    invalidReason = reason,
                    endTimestamp = System.currentTimeMillis(),
                    endPercentage = getBatteryPercentage(),
                    endChargeCounter = getChargeCounter()
                )

                sessionRepository.updateSession(invalidSession)
                Timber.w("Session marked invalid: $reason")

            } catch (e: Exception) {
                Timber.e(e, "Failed to mark session invalid")
            } finally {
                currentSessionId = null
                chargingMeasurements.clear()
                stopSelf()
            }
        }
    }

    private fun updateNotification(percentage: Int, temperature: Float) {
        val notification = notificationHelper.createMonitoringNotification(
            percentage = percentage,
            temperature = temperature
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationHelper.MONITORING_NOTIFICATION_ID, notification)
    }

    private fun updateDischargeNotification(percentage: Int, temperature: Float) {
        val notification = notificationHelper.createDischargeMonitoringNotification(
            percentage = percentage,
            temperature = temperature
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NotificationHelper.DISCHARGE_MONITORING_NOTIFICATION_ID, notification)
    }

    // Battery 정보 읽기 메서드들
    private fun getChargeCounter(): Long? {
        return try {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (e: Exception) {
            Timber.w("CHARGE_COUNTER not supported")
            null
        }
    }

    private fun getCurrent(): Int? {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (e: Exception) {
            null
        }
    }

    private fun getTemperature(): Float {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f
    }

    private fun getVoltage(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
    }

    private fun getBatteryPercentage(): Int {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) {
            ((level / scale.toFloat()) * 100).toInt()
        } else {
            0
        }
    }

    private fun isCharging(): Boolean {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getChargerType(): String {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> "Unknown"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(chargingMonitoringRunnable)
        handler.removeCallbacks(dischargeMonitoringRunnable)
        serviceScope.cancel()
        Timber.d("BatteryMonitoringService destroyed")
    }

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_START_DISCHARGE_MONITORING = "START_DISCHARGE_MONITORING"
        const val ACTION_STOP_DISCHARGE_MONITORING = "STOP_DISCHARGE_MONITORING"

        private const val MONITORING_INTERVAL_MS = 30_000L // 30초 (충전 시)
        private const val DISCHARGE_MONITORING_INTERVAL_MS = 300_000L // 5분 (방전 시)
        private const val MAX_SAFE_TEMPERATURE = 50f // 50°C
    }
}