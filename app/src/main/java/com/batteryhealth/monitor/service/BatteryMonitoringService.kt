// service/BatteryMonitoringService.kt 수정
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

    private var currentSessionId: Long? = null
    private val measurements = mutableListOf<BatteryMeasurement>()

    // 세션 시작 시간 추적
    private var sessionStartTime: Long = 0L
    // 마지막으로 측정된 배터리 퍼센트
    private var lastPercentage: Int = 0

    private val handler = Handler(Looper.getMainLooper())
    private val monitoringRunnable = object : Runnable {
        override fun run() {
            collectBatteryData()
            handler.postDelayed(this, MONITORING_INTERVAL_MS)
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
            ACTION_START_MONITORING -> startMonitoring()
            ACTION_STOP_MONITORING -> stopMonitoring()
        }

        return START_STICKY
    }

    private fun startMonitoring() {
        Timber.i("Starting battery monitoring")

        // 이미 모니터링 중이면 무시
        if (currentSessionId != null) {
            Timber.w("Already monitoring session: $currentSessionId")
            return
        }

        // Foreground 알림 표시
        startForeground(
            NotificationHelper.MONITORING_NOTIFICATION_ID,
            notificationHelper.createMonitoringNotification()
        )

        sessionStartTime = System.currentTimeMillis()
        val startPercentage = getBatteryPercentage()
        lastPercentage = startPercentage

        // 새 세션 생성
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
                measurements.clear()

                Timber.d("Created new session: $currentSessionId (Start: $startPercentage%)")

                // 모니터링 시작
                handler.post(monitoringRunnable)

            } catch (e: Exception) {
                Timber.e(e, "Failed to start monitoring")
                stopSelf()
            }
        }
    }

    private fun collectBatteryData() {
        val sessionId = currentSessionId ?: return

        val chargeCounter = getChargeCounter()
        val temperature = getTemperature()
        val voltage = getVoltage()
        val percentage = getBatteryPercentage()
        val current = getCurrent()

        Timber.v("Battery data: $percentage%, ${temperature}°C, ${voltage}mV")

        // 퍼센트 업데이트
        lastPercentage = percentage

        // 고온 체크
        if (temperature > MAX_SAFE_TEMPERATURE) {
            Timber.w("High temperature detected: ${temperature}°C")
            markSessionInvalid("고온: ${temperature}°C")
            return
        }

        // 측정값 저장
        val measurement = BatteryMeasurement(
            sessionId = sessionId,
            timestamp = System.currentTimeMillis(),
            chargeCounter = chargeCounter,
            temperature = temperature,
            voltage = voltage,
            percentage = percentage,
            current = current
        )

        measurements.add(measurement)

        // DB에 저장 (배치) - 즉시 저장으로 변경
        if (measurements.size >= 5) { // 10에서 5로 변경하여 더 자주 저장
            serviceScope.launch {
                sessionRepository.insertMeasurements(measurements.toList())
                Timber.d("Saved ${measurements.size} measurements to DB")
                measurements.clear()
            }
        }

        // 충전 완료 또는 중단 체크
        val isCurrentlyCharging = isCharging()
        if (!isCurrentlyCharging || percentage == 100) {
            Timber.i("Charging completed or stopped (Charging: $isCurrentlyCharging, Percentage: $percentage%)")
            finalizeSession()
            return
        }

        // 알림 업데이트
        updateNotification(percentage, temperature)
    }

    private fun finalizeSession() {
        val sessionId = currentSessionId ?: return

        Timber.i("Finalizing session: $sessionId")

        // 즉시 모니터링 중지
        handler.removeCallbacks(monitoringRunnable)

        serviceScope.launch {
            try {
                // 남은 측정값 모두 저장
                if (measurements.isNotEmpty()) {
                    sessionRepository.insertMeasurements(measurements)
                    Timber.d("Saved remaining ${measurements.size} measurements")
                }

                // 세션 정보 가져오기
                val session = sessionRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.e("Session not found: $sessionId")
                    currentSessionId = null
                    measurements.clear()
                    stopSelf()
                    return@launch
                }

                // 현재 배터리 상태 가져오기
                val endChargeCounter = getChargeCounter()
                val endPercentage = getBatteryPercentage()
                val endTime = System.currentTimeMillis()

                Timber.d("Session finalize - Start: ${session.startPercentage}%, End: $endPercentage%")

                // 추정 용량 계산
                val estimatedCapacity = healthCalculator.calculateEstimatedCapacity(
                    startCounter = session.startChargeCounter,
                    endCounter = endChargeCounter,
                    startPercentage = session.startPercentage,
                    endPercentage = endPercentage
                )

                // 모든 측정값 가져오기
                val allMeasurements = sessionRepository.getMeasurementsBySession(sessionId)
                Timber.d("Total measurements for session: ${allMeasurements.size}")

                // 평균 및 최대값 계산
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

                // 세션 업데이트 - 중요: endTimestamp와 endPercentage 반드시 설정!
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

                val durationSeconds = (endTime - sessionStartTime) / 1000
                val chargeChange = endPercentage - session.startPercentage

                Timber.i("Session finalized successfully:")
                Timber.i("  Duration: ${durationSeconds}s (${durationSeconds / 60} min)")
                Timber.i("  Charge: ${session.startPercentage}% -> $endPercentage% (+$chargeChange%)")
                Timber.i("  Estimated capacity: $estimatedCapacity mAh")
                Timber.i("  Measurements: ${allMeasurements.size}")

                // 완료 알림
                notificationHelper.showSessionCompletedNotification(
                    sessionId = sessionId,
                    estimatedCapacity = estimatedCapacity
                )

            } catch (e: Exception) {
                Timber.e(e, "Failed to finalize session")
            } finally {
                currentSessionId = null
                measurements.clear()
                lastPercentage = 0
                stopSelf()
            }
        }
    }

    private fun markSessionInvalid(reason: String) {
        val sessionId = currentSessionId ?: return

        serviceScope.launch {
            try {
                val session = sessionRepository.getSessionById(sessionId) ?: return@launch

                val endTime = System.currentTimeMillis()
                val endPercentage = getBatteryPercentage()

                val invalidSession = session.copy(
                    isValid = false,
                    invalidReason = reason,
                    endTimestamp = endTime,
                    endPercentage = endPercentage,
                    endChargeCounter = getChargeCounter()
                )

                sessionRepository.updateSession(invalidSession)

                Timber.w("Session marked invalid: $reason (Start: ${session.startPercentage}%, End: $endPercentage%)")

            } catch (e: Exception) {
                Timber.e(e, "Failed to mark session invalid")
            } finally {
                currentSessionId = null
                measurements.clear()
                lastPercentage = 0
                stopSelf()
            }
        }
    }

    private fun stopMonitoring() {
        Timber.i("Stopping battery monitoring")
        handler.removeCallbacks(monitoringRunnable)

        // 진행 중인 세션이 있으면 종료
        if (currentSessionId != null) {
            finalizeSession()
        } else {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
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
        handler.removeCallbacks(monitoringRunnable)
        serviceScope.cancel()
        Timber.d("BatteryMonitoringService destroyed")
    }

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"

        private const val MONITORING_INTERVAL_MS = 30_000L // 30초
        private const val MAX_SAFE_TEMPERATURE = 40f // 40°C
    }
}