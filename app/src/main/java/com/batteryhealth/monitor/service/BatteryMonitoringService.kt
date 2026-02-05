// service/BatteryMonitoringService.kt (프로덕션 레벨)
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
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

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

    // 충전 완료 감지 개선을 위한 변수
    private var fullChargeDetectedCount = 0
    private var consecutiveNotChargingCount = 0

    // 방전 모니터링 관련
    private var isDischargeMonitoring = false
    private val dischargeMeasurements = mutableListOf<BatteryMeasurement>()
    private var lastDischargeSnapshot: BatterySnapshot? = null
    private var dischargeStartSnapshot: BatterySnapshot? = null

    private val handler = Handler(Looper.getMainLooper())

    // 🔥 안전장치: Foreground 시작 보장
    private var isForegroundStarted = false
    private var isInitialized = false

    // 🔥 안전장치: 세션 타임아웃 (24시간)
    private val sessionTimeoutRunnable = Runnable {
        Timber.w("⚠️ Session timeout reached (24h) - forcing finalization")
        if (currentSessionId != null) {
            finalizeChargingSession(reason = "타임아웃 (24시간 초과)")
        }
    }

    // 충전 모니터링 (30초 간격)
    private val chargingMonitoringRunnable = object : Runnable {
        override fun run() {
            try {
                collectChargingData()
                handler.postDelayed(this, MONITORING_INTERVAL_MS)
            } catch (e: Exception) {
                Timber.e(e, "Error in charging monitoring runnable")
                // 에러 발생해도 계속 실행
                handler.postDelayed(this, MONITORING_INTERVAL_MS)
            }
        }
    }

    // 방전 모니터링 (5분 간격)
    private val dischargeMonitoringRunnable = object : Runnable {
        override fun run() {
            try {
                collectDischargeData()
                handler.postDelayed(this, DISCHARGE_MONITORING_INTERVAL_MS)
            } catch (e: Exception) {
                Timber.e(e, "Error in discharge monitoring runnable")
                handler.postDelayed(this, DISCHARGE_MONITORING_INTERVAL_MS)
            }
        }
    }

    // 🔥 안전장치: Watchdog - 5분마다 상태 점검
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkServiceHealth()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("🚀 BatteryMonitoringService created")

        // 🔥 안전장치: Watchdog 시작
        handler.post(watchdogRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "NULL"
        Timber.d("📨 onStartCommand: action=$action, currentSessionId=$currentSessionId, isForeground=$isForegroundStarted")

        // 🔥 엣지 케이스 1: 액션 없이 재시작된 경우 (START_STICKY)
        if (intent == null || action == "NULL") {
            Timber.w("⚠️ Service restarted without intent (START_STICKY)")
            handleServiceRestart()
            return START_STICKY
        }

        when (action) {
            ACTION_START_MONITORING -> {
                Timber.d("📥 Received START_MONITORING command")
                startChargingMonitoring()
            }
            ACTION_STOP_MONITORING -> {
                Timber.d("📥 Received STOP_MONITORING command")
                stopChargingMonitoring()
            }
            ACTION_START_DISCHARGE_MONITORING -> {
                Timber.d("📥 Received START_DISCHARGE_MONITORING command")
                startDischargeMonitoring()
            }
            ACTION_STOP_DISCHARGE_MONITORING -> {
                Timber.d("📥 Received STOP_DISCHARGE_MONITORING command")
                stopDischargeMonitoring()
            }
            else -> {
                Timber.w("⚠️ Unknown action: $action")
            }
        }

        return START_STICKY
    }

    /**
     * 🔥 엣지 케이스: Service가 시스템에 의해 재시작된 경우
     */
    private fun handleServiceRestart() {
        Timber.i("🔄 Handling service restart")

        if (!isForegroundStarted) {
            startForeground(
                NotificationHelper.MONITORING_NOTIFICATION_ID,
                notificationHelper.createMonitoringNotification()
            )
            isForegroundStarted = true
        }

        serviceScope.launch {
            try {
                // DB에서 진행 중인 세션 확인
                val ongoingSession = sessionRepository.getOngoingSession()

                if (ongoingSession != null) {
                    Timber.i("♻️ Found ongoing session after restart: ${ongoingSession.id}")

                    // 세션이 너무 오래된 경우 (24시간 이상)
                    val sessionAge = System.currentTimeMillis() - ongoingSession.startTimestamp
                    if (sessionAge > MAX_SESSION_DURATION_MS) {
                        Timber.w("⚠️ Ongoing session is too old (${sessionAge / 1000 / 60 / 60}h), finalizing")
                        finalizeStaleSession(ongoingSession)
                    } else if (isCharging()) {
                        // 현재 충전 중이면 세션 재개
                        Timber.i("♻️ Resuming session ${ongoingSession.id}")
                        resumeSession(ongoingSession)
                    } else {
                        // 충전 중이 아니면 세션 종료
                        Timber.i("♻️ Not charging, finalizing session ${ongoingSession.id}")
                        finalizeStaleSession(ongoingSession)
                    }
                } else {
                    // 진행 중인 세션이 없으면 현재 상태 확인
                    if (isCharging()) {
                        Timber.i("♻️ Currently charging but no session, starting new one")
                        startChargingMonitoring()
                    } else {
                        Timber.d("♻️ Not charging, stopping service")
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error handling service restart")
                stopSelf()
            }
        }
    }

    /**
     * 충전 모니터링 시작
     */
    private fun startChargingMonitoring() {
        Timber.i("▶️ Starting charging monitoring - currentSessionId=$currentSessionId")

        // 🔥 안전장치: 초기화 중복 방지
        if (!isInitialized) {
            serviceScope.launch {
                cleanupStaleSessions()
            }
            isInitialized = true
        }

        // 🔥 엣지 케이스 2: 이미 세션이 진행 중인 경우
        if (currentSessionId != null) {
            Timber.w("⚠️ Already monitoring session $currentSessionId")

            serviceScope.launch {
                val session = sessionRepository.getSessionById(currentSessionId!!)
                if (session != null && session.endTimestamp == null) {
                    Timber.i("✅ Current session is valid, continuing")
                    return@launch
                } else {
                    Timber.w("⚠️ Current session is invalid, resetting")
                    resetChargingState()
                }
            }
        }

        // **중요: startForeground()를 가장 먼저 호출 (5초 제한)**
        if (!isForegroundStarted) {
            startForeground(
                NotificationHelper.MONITORING_NOTIFICATION_ID,
                notificationHelper.createMonitoringNotification()
            )
            isForegroundStarted = true
            Timber.d("✅ startForeground() called successfully")
        }

        // 이후 비동기 작업 진행
        serviceScope.launch {
            try {
                // 🔥 엣지 케이스 3: DB에 진행 중인 세션이 있는 경우
                val ongoingSession = sessionRepository.getOngoingSession()
                Timber.d("🔍 Ongoing session check: ${ongoingSession?.id}")

                if (ongoingSession != null) {
                    val sessionAge = System.currentTimeMillis() - ongoingSession.startTimestamp

                    // 24시간 이상 된 세션은 종료
                    if (sessionAge > MAX_SESSION_DURATION_MS) {
                        Timber.w("⚠️ Found stale session ${ongoingSession.id} (age: ${sessionAge / 1000 / 60 / 60}h)")
                        finalizeStaleSession(ongoingSession)
                    } else if (currentSessionId == ongoingSession.id) {
                        Timber.i("♻️ Resuming current session: ${ongoingSession.id}")
                        resumeSession(ongoingSession)
                        return@launch
                    } else {
                        Timber.w("⚠️ Found different ongoing session ${ongoingSession.id}, finalizing it first")
                        finalizeStaleSession(ongoingSession)
                    }
                }

                // 🔥 엣지 케이스 4: 실제로 충전 중이 아닌 경우
                val isCurrentlyCharging = isCharging()
                Timber.d("🔌 Is currently charging: $isCurrentlyCharging")

                if (!isCurrentlyCharging) {
                    Timber.w("⚠️ Not charging, cannot start monitoring")
                    stopSelf()
                    return@launch
                }

                // 새 세션 시작
                sessionStartTime = System.currentTimeMillis()
                val startPercentage = getBatteryPercentage()

                // 🔥 엣지 케이스 5: 배터리 퍼센트가 비정상적인 경우
                if (startPercentage < 0 || startPercentage > 100) {
                    Timber.e("❌ Invalid battery percentage: $startPercentage%")
                    stopSelf()
                    return@launch
                }

                lastPercentage = startPercentage

                // 충전 완료 카운터 초기화
                fullChargeDetectedCount = 0
                consecutiveNotChargingCount = 0

                Timber.d("🆕 Creating new session - startPercentage: $startPercentage%")

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

                Timber.i("✅ Created charging session: $currentSessionId (Start: $startPercentage%)")

                // 모니터링 시작
                handler.post(chargingMonitoringRunnable)

                // 🔥 안전장치: 24시간 타임아웃 설정
                handler.postDelayed(sessionTimeoutRunnable, MAX_SESSION_DURATION_MS)

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to start charging monitoring")
                resetChargingState()
                stopSelf()
            }
        }
    }

    /**
     * 🔥 세션 재개 (앱 재시작, Service 재시작 등)
     */
    private suspend fun resumeSession(session: ChargingSession) = withContext(Dispatchers.Main) {
        Timber.i("♻️ Resuming session ${session.id}")

        currentSessionId = session.id
        sessionStartTime = session.startTimestamp
        lastPercentage = session.startPercentage
        fullChargeDetectedCount = 0
        consecutiveNotChargingCount = 0

        // 측정 재개
        handler.post(chargingMonitoringRunnable)

        // 타임아웃 재설정
        val elapsed = System.currentTimeMillis() - sessionStartTime
        val remaining = MAX_SESSION_DURATION_MS - elapsed
        if (remaining > 0) {
            handler.postDelayed(sessionTimeoutRunnable, remaining)
        } else {
            handler.post(sessionTimeoutRunnable)
        }
    }

    /**
     * 🔥 오래된 세션 정리
     */
    private suspend fun cleanupStaleSessions() {
        try {
            val allSessions = sessionRepository.getRecentSessions(100)
            var cleanedCount = 0

            for (session in allSessions) {
                if (session.endTimestamp == null) {
                    val age = System.currentTimeMillis() - session.startTimestamp

                    if (age > MAX_SESSION_DURATION_MS) {
                        Timber.w("🧹 Cleaning up stale session ${session.id} (age: ${age / 1000 / 60 / 60}h)")

                        val updated = session.copy(
                            endTimestamp = session.startTimestamp + MAX_SESSION_DURATION_MS,
                            endPercentage = session.startPercentage,
                            isValid = false,
                            invalidReason = "시스템 정리 (24시간 타임아웃)"
                        )

                        sessionRepository.updateSession(updated)
                        cleanedCount++
                    }
                }
            }

            if (cleanedCount > 0) {
                Timber.i("🧹 Cleaned up $cleanedCount stale sessions")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to cleanup stale sessions")
        }
    }

    /**
     * 🔥 오래된 세션 종료
     */
    private suspend fun finalizeStaleSession(session: ChargingSession) {
        try {
            val updated = session.copy(
                endTimestamp = System.currentTimeMillis(),
                endPercentage = getBatteryPercentage(),
                endChargeCounter = getChargeCounter(),
                isValid = false,
                invalidReason = "비정상 종료 (시스템 재시작 또는 타임아웃)"
            )

            sessionRepository.updateSession(updated)
            Timber.i("🧹 Finalized stale session ${session.id}")

        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to finalize stale session")
        }
    }

    /**
     * 충전 중 데이터 수집
     */
    private fun collectChargingData() {
        val sessionId = currentSessionId ?: run {
            Timber.w("⚠️ collectChargingData called but no session")
            handler.removeCallbacks(chargingMonitoringRunnable)
            return
        }

        try {
            val chargeCounter = getChargeCounter()
            val temperature = getTemperature()
            val voltage = getVoltage()
            val percentage = getBatteryPercentage()
            val current = getCurrent()

            Timber.v("📊 Charging data: $percentage%, ${temperature}°C, ${voltage}mV")

            // 🔥 엣지 케이스: 배터리 데이터 검증
            if (percentage < 0 || percentage > 100) {
                Timber.e("❌ Invalid percentage: $percentage%")
                return
            }

            if (temperature < -20f || temperature > 100f) {
                Timber.e("❌ Invalid temperature: ${temperature}°C")
                return
            }

            lastPercentage = percentage

            // 고온 체크
            if (temperature > MAX_SAFE_TEMPERATURE) {
                Timber.w("🔥 High temperature detected: ${temperature}°C")
                markSessionInvalid("고온: ${temperature}°C")
                return
            }

            // 측정 데이터 저장
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

            // 배치로 DB에 저장
            if (chargingMeasurements.size >= MEASUREMENT_BATCH_SIZE) {
                serviceScope.launch {
                    try {
                        sessionRepository.insertMeasurements(chargingMeasurements.toList())
                        Timber.d("💾 Saved ${chargingMeasurements.size} measurements to DB")
                        chargingMeasurements.clear()
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Failed to save measurements")
                        // 실패해도 측정은 계속 (메모리에 유지)
                    }
                }
            }

            // 충전 완료 감지 로직
            val isCurrentlyCharging = isCharging()

            if (percentage >= 100) {
                fullChargeDetectedCount++
                Timber.d("🔋 Full charge detected: count=$fullChargeDetectedCount")
            } else {
                fullChargeDetectedCount = 0
            }

            if (!isCurrentlyCharging) {
                consecutiveNotChargingCount++
                Timber.d("⏸️ Not charging detected: count=$consecutiveNotChargingCount")
            } else {
                consecutiveNotChargingCount = 0
            }

            // 종료 조건
            val shouldFinalize = (fullChargeDetectedCount >= FULL_CHARGE_THRESHOLD) ||
                    (consecutiveNotChargingCount >= NOT_CHARGING_THRESHOLD)

            if (shouldFinalize) {
                val reason = if (fullChargeDetectedCount >= FULL_CHARGE_THRESHOLD) {
                    "100% 충전 완료"
                } else {
                    "충전 중단 감지"
                }
                Timber.i("🏁 Finalizing session: $reason")
                finalizeChargingSession(reason = reason)
                return
            }

            updateNotification(percentage, temperature)

        } catch (e: Exception) {
            Timber.e(e, "❌ Error collecting charging data")
            // 에러 발생해도 계속 모니터링
        }
    }

    /**
     * 충전 세션 종료
     */
    private fun finalizeChargingSession(reason: String = "정상 종료") {
        val sessionId = currentSessionId ?: run {
            Timber.w("⚠️ finalizeChargingSession called but currentSessionId is null")
            return
        }

        Timber.i("🏁 Finalizing charging session: $sessionId (reason: $reason)")

        handler.removeCallbacks(chargingMonitoringRunnable)
        handler.removeCallbacks(sessionTimeoutRunnable)

        serviceScope.launch {
            try {
                // 남은 측정 데이터 저장
                if (chargingMeasurements.isNotEmpty()) {
                    sessionRepository.insertMeasurements(chargingMeasurements)
                    Timber.d("💾 Saved remaining ${chargingMeasurements.size} measurements")
                }

                val session = sessionRepository.getSessionById(sessionId)
                if (session == null) {
                    Timber.e("❌ Session not found: $sessionId")
                    resetChargingState()
                    stopSelf()
                    return@launch
                }

                val endChargeCounter = getChargeCounter()
                val endPercentage = getBatteryPercentage()
                val endTime = System.currentTimeMillis()

                Timber.d("📊 Session end - percentage: $endPercentage%, counter: $endChargeCounter")

                // 🔥 엣지 케이스: 세션 시간이 너무 짧은 경우
                val sessionDuration = endTime - session.startTimestamp
                if (sessionDuration < MIN_SESSION_DURATION_MS) {
                    Timber.w("⚠️ Session too short: ${sessionDuration / 1000}s, marking invalid")
                    markSessionInvalid("충전 시간 너무 짧음 (${sessionDuration / 1000}초)")
                    return@launch
                }

                // 🔥 엣지 케이스: 배터리 변화가 없는 경우
                val percentageChange = abs(endPercentage - session.startPercentage)
                if (percentageChange < MIN_PERCENTAGE_CHANGE) {
                    Timber.w("⚠️ Insufficient battery change: $percentageChange%, marking invalid")
                    markSessionInvalid("배터리 변화 부족 ($percentageChange%)")
                    return@launch
                }

                // 용량 계산
                val estimatedCapacity = healthCalculator.calculateEstimatedCapacity(
                    startCounter = session.startChargeCounter,
                    endCounter = endChargeCounter,
                    startPercentage = session.startPercentage,
                    endPercentage = endPercentage
                )

                Timber.d("💡 Estimated capacity: $estimatedCapacity mAh")

                // 모든 측정값에서 통계 계산
                val allMeasurements = sessionRepository.getMeasurementsBySession(sessionId)
                Timber.d("📊 Total measurements for session: ${allMeasurements.size}")

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

                // 세션 업데이트
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

                Timber.i("✅ Charging session finalized successfully: " +
                        "ID=$sessionId, capacity=$estimatedCapacity mAh, " +
                        "${session.startPercentage}% → ${endPercentage}%, " +
                        "duration=${sessionDuration / 1000}s")

                // 완료 알림
                notificationHelper.showSessionCompletedNotification(
                    sessionId = sessionId,
                    estimatedCapacity = estimatedCapacity
                )

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to finalize charging session")
            } finally {
                // 상태 완전 초기화
                resetChargingState()
                Timber.d("🔄 Charging state reset, stopping service")

                // Service 완전 종료
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundStarted = false
                stopSelf()
            }
        }
    }

    /**
     * 🔥 서비스 건강 상태 점검 (Watchdog)
     */
    private fun checkServiceHealth() {
        try {
            Timber.v("🐕 Watchdog: Checking service health")

            // 1. Foreground 상태 확인
            if (currentSessionId != null && !isForegroundStarted) {
                Timber.w("⚠️ Watchdog: Session active but not in foreground, fixing")
                startForeground(
                    NotificationHelper.MONITORING_NOTIFICATION_ID,
                    notificationHelper.createMonitoringNotification()
                )
                isForegroundStarted = true
            }

            // 2. 세션 일관성 확인
            if (currentSessionId != null) {
                serviceScope.launch {
                    val session = sessionRepository.getSessionById(currentSessionId!!)
                    if (session == null) {
                        Timber.w("⚠️ Watchdog: Session ID exists but not in DB, resetting")
                        resetChargingState()
                    } else if (session.endTimestamp != null) {
                        Timber.w("⚠️ Watchdog: Session already ended, resetting")
                        resetChargingState()
                    }
                }
            }

            // 3. 충전 상태 불일치 확인
            if (currentSessionId != null && !isCharging()) {
                consecutiveNotChargingCount++
                if (consecutiveNotChargingCount > NOT_CHARGING_THRESHOLD * 2) {
                    Timber.w("⚠️ Watchdog: Not charging for too long, finalizing")
                    finalizeChargingSession(reason = "Watchdog: 장시간 미충전")
                }
            }

        } catch (e: Exception) {
            Timber.e(e, "❌ Error in watchdog")
        }
    }

    /**
     * 충전 모니터링 상태 초기화
     */
    private fun resetChargingState() {
        Timber.d("🔄 Resetting charging state - before: currentSessionId=$currentSessionId")

        handler.removeCallbacks(chargingMonitoringRunnable)
        handler.removeCallbacks(sessionTimeoutRunnable)

        currentSessionId = null
        chargingMeasurements.clear()
        lastPercentage = 0
        fullChargeDetectedCount = 0
        consecutiveNotChargingCount = 0

        Timber.d("🔄 Resetting charging state - after: currentSessionId=$currentSessionId")
    }

    /**
     * 방전 모니터링 시작
     */
    private fun startDischargeMonitoring() {
        Timber.i("▶️ Starting discharge monitoring")

        if (isDischargeMonitoring) {
            Timber.w("⚠️ Already monitoring discharge")
            return
        }

        // **중요: startForeground()를 가장 먼저 호출**
        if (!isForegroundStarted) {
            startForeground(
                NotificationHelper.DISCHARGE_MONITORING_NOTIFICATION_ID,
                notificationHelper.createDischargeMonitoringNotification()
            )
            isForegroundStarted = true
        }

        isDischargeMonitoring = true
        dischargeMeasurements.clear()

        // 초기 스냅샷 생성
        dischargeStartSnapshot = createBatterySnapshot()
        lastDischargeSnapshot = dischargeStartSnapshot

        Timber.d("✅ Discharge monitoring started at ${dischargeStartSnapshot?.percentage}%")

        handler.post(dischargeMonitoringRunnable)
    }

    /**
     * 방전 중 데이터 수집
     */
    private fun collectDischargeData() {
        if (!isDischargeMonitoring) return

        try {
            val currentSnapshot = createBatterySnapshot()

            lastDischargeSnapshot?.let { lastSnapshot ->
                val percentageDelta = lastSnapshot.percentageDelta(currentSnapshot)
                val timeDeltaMinutes = lastSnapshot.timeDeltaSeconds(currentSnapshot) / 60

                Timber.d("📊 Discharge: -${percentageDelta}% in ${timeDeltaMinutes}min, " +
                        "Current: ${currentSnapshot.percentage}%, Temp: ${currentSnapshot.temperature}°C")

                // 배터리 건강도 추정에 사용할 데이터 저장
                if (percentageDelta >= MIN_DISCHARGE_PERCENTAGE) {
                    serviceScope.launch {
                        saveDischargeDataPoint(lastSnapshot, currentSnapshot)
                    }
                }
            }

            lastDischargeSnapshot = currentSnapshot

            // 충전이 시작되면 방전 모니터링 중지
            if (isCharging()) {
                Timber.i("🔌 Charging started, stopping discharge monitoring")
                stopDischargeMonitoring()
            }

            // 배터리가 너무 낮으면 중지
            if (currentSnapshot.percentage <= MIN_BATTERY_PERCENTAGE) {
                Timber.w("⚠️ Battery too low (${currentSnapshot.percentage}%), stopping discharge monitoring")
                stopDischargeMonitoring()
            }

            updateDischargeNotification(currentSnapshot.percentage, currentSnapshot.temperature)

        } catch (e: Exception) {
            Timber.e(e, "❌ Error collecting discharge data")
        }
    }

    /**
     * 방전 데이터 포인트 저장
     */
    private suspend fun saveDischargeDataPoint(
        startSnapshot: BatterySnapshot,
        endSnapshot: BatterySnapshot
    ) {
        try {
            val percentageDelta = startSnapshot.percentageDelta(endSnapshot)
            val chargeCounterDelta = startSnapshot.chargeCounterDeltaMah(endSnapshot)

            if (chargeCounterDelta != null && percentageDelta > 0) {
                val estimatedCapacity = (chargeCounterDelta * 100) / percentageDelta

                // 🔥 엣지 케이스: 비현실적인 용량 필터링
                if (estimatedCapacity < MIN_REALISTIC_CAPACITY || estimatedCapacity > MAX_REALISTIC_CAPACITY) {
                    Timber.w("⚠️ Unrealistic discharge capacity: $estimatedCapacity mAh, skipping")
                    return
                }

                Timber.d("💡 Discharge capacity estimate: $estimatedCapacity mAh " +
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
                    chargerType = "DISCHARGE",
                    chargingSpeed = null
                )

                sessionRepository.insertSession(dischargeSession)
                Timber.i("✅ Saved discharge data point")
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to save discharge data point")
        }
    }

    /**
     * 방전 모니터링 중지
     */
    private fun stopDischargeMonitoring() {
        Timber.i("⏹️ Stopping discharge monitoring")

        handler.removeCallbacks(dischargeMonitoringRunnable)
        isDischargeMonitoring = false
        dischargeMeasurements.clear()
        lastDischargeSnapshot = null
        dischargeStartSnapshot = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        isForegroundStarted = false
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
        Timber.i("⏹️ Stopping charging monitoring - currentSessionId=$currentSessionId")

        handler.removeCallbacks(chargingMonitoringRunnable)
        handler.removeCallbacks(sessionTimeoutRunnable)

        if (currentSessionId != null) {
            Timber.d("📝 Current session exists, finalizing...")
            finalizeChargingSession(reason = "수동 중지")
        } else {
            Timber.d("🔄 No current session, stopping service directly")
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundStarted = false
            stopSelf()
        }
    }

    private fun markSessionInvalid(reason: String) {
        val sessionId = currentSessionId ?: return

        Timber.w("⚠️ Marking session $sessionId as invalid: $reason")

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
                Timber.i("✅ Session $sessionId marked invalid: $reason")

            } catch (e: Exception) {
                Timber.e(e, "❌ Failed to mark session invalid")
            } finally {
                resetChargingState()
                stopSelf()
            }
        }
    }

    private fun updateNotification(percentage: Int, temperature: Float) {
        try {
            val notification = notificationHelper.createMonitoringNotification(
                percentage = percentage,
                temperature = temperature
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NotificationHelper.MONITORING_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to update notification")
        }
    }

    private fun updateDischargeNotification(percentage: Int, temperature: Float) {
        try {
            val notification = notificationHelper.createDischargeMonitoringNotification(
                percentage = percentage,
                temperature = temperature
            )

            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NotificationHelper.DISCHARGE_MONITORING_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to update discharge notification")
        }
    }

    // Battery 정보 읽기 메서드들 (예외 처리 강화)
    private fun getChargeCounter(): Long? {
        return try {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        } catch (e: Exception) {
            Timber.v("⚠️ CHARGE_COUNTER not supported: ${e.message}")
            null
        }
    }

    private fun getCurrent(): Int? {
        return try {
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        } catch (e: Exception) {
            Timber.v("⚠️ CURRENT_NOW not supported: ${e.message}")
            null
        }
    }

    private fun getTemperature(): Float {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            temp / 10f
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get temperature")
            25f // 기본값
        }
    }

    private fun getVoltage(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get voltage")
            4000 // 기본값
        }
    }

    private fun getBatteryPercentage(): Int {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) {
                ((level / scale.toFloat()) * 100).toInt()
            } else {
                0
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get battery percentage")
            0
        }
    }

    private fun isCharging(): Boolean {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to check charging status")
            false
        }
    }

    private fun getChargerType(): String {
        return try {
            val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            when (intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "AC"
                BatteryManager.BATTERY_PLUGGED_USB -> "USB"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
                else -> "Unknown"
            }
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to get charger type")
            "Unknown"
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        Timber.d("🛑 BatteryMonitoringService destroying")

        // 모든 핸들러 콜백 제거
        handler.removeCallbacks(chargingMonitoringRunnable)
        handler.removeCallbacks(dischargeMonitoringRunnable)
        handler.removeCallbacks(sessionTimeoutRunnable)
        handler.removeCallbacks(watchdogRunnable)

        // 🔥 안전장치: 진행 중인 세션이 있으면 로그 기록
        if (currentSessionId != null) {
            Timber.w("⚠️ Service destroyed with active session: $currentSessionId")
            // Note: onDestroy에서는 비동기 작업을 신뢰할 수 없으므로 로그만 기록
        }

        // 상태 초기화
        resetChargingState()

        serviceScope.cancel()

        Timber.d("🛑 BatteryMonitoringService destroyed")
    }

    companion object {
        const val ACTION_START_MONITORING = "START_MONITORING"
        const val ACTION_STOP_MONITORING = "STOP_MONITORING"
        const val ACTION_START_DISCHARGE_MONITORING = "START_DISCHARGE_MONITORING"
        const val ACTION_STOP_DISCHARGE_MONITORING = "STOP_DISCHARGE_MONITORING"

        // 모니터링 간격
        private const val MONITORING_INTERVAL_MS = 30_000L // 30초
        private const val DISCHARGE_MONITORING_INTERVAL_MS = 300_000L // 5분
        private const val WATCHDOG_INTERVAL_MS = 300_000L // 5분

        // 안전 임계값
        private const val MAX_SAFE_TEMPERATURE = 50f // 50°C
        private const val MAX_SESSION_DURATION_MS = 24 * 60 * 60 * 1000L // 24시간
        private const val MIN_SESSION_DURATION_MS = 60 * 1000L // 1분
        private const val MIN_PERCENTAGE_CHANGE = 1 // 최소 1% 변화
        private const val MIN_DISCHARGE_PERCENTAGE = 5 // 방전 최소 5%
        private const val MIN_BATTERY_PERCENTAGE = 10 // 최소 배터리 10%

        // 충전 완료 감지
        private const val FULL_CHARGE_THRESHOLD = 3 // 100% 3회 연속
        private const val NOT_CHARGING_THRESHOLD = 2 // 비충전 2회 연속

        // 배치 크기
        private const val MEASUREMENT_BATCH_SIZE = 5

        // 용량 검증
        private const val MIN_REALISTIC_CAPACITY = 500 // mAh
        private const val MAX_REALISTIC_CAPACITY = 20000 // mAh
    }
}