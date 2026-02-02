// ui/main/MainViewModel.kt (개선 버전)
package com.batteryhealth.monitor.ui.main

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec
import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import com.batteryhealth.monitor.domain.model.BatteryHealthResult
import com.batteryhealth.monitor.domain.usecase.CalculateBatteryHealthUseCase
import com.batteryhealth.monitor.domain.usecase.GetDeviceSpecUseCase
import com.batteryhealth.monitor.domain.usecase.StartMonitoringUseCase
import com.batteryhealth.monitor.service.BatteryMonitoringService
import com.batteryhealth.monitor.util.BatteryInfo
import com.batteryhealth.monitor.util.BatteryUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    application: Application,
    private val calculateHealthUseCase: CalculateBatteryHealthUseCase,
    private val getDeviceSpecUseCase: GetDeviceSpecUseCase,
    private val startMonitoringUseCase: StartMonitoringUseCase,
    private val sessionRepository: ChargingSessionRepository
) : AndroidViewModel(application) {

    private val _batteryHealthResult = MutableLiveData<BatteryHealthResult?>()
    val batteryHealthResult: LiveData<BatteryHealthResult?> = _batteryHealthResult

    private val _deviceSpec = MutableLiveData<DeviceBatterySpec>()
    val deviceSpec: LiveData<DeviceBatterySpec> = _deviceSpec

    private val _currentBatteryInfo = MutableLiveData<BatteryInfo>()
    val currentBatteryInfo: LiveData<BatteryInfo> = _currentBatteryInfo

    private val _isMonitoring = MutableLiveData<Boolean>(false)
    val isMonitoring: LiveData<Boolean> = _isMonitoring

    private val _isDischargeMonitoring = MutableLiveData<Boolean>(false)
    val isDischargeMonitoring: LiveData<Boolean> = _isDischargeMonitoring

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    private var isRealTimeUpdateActive = false

    init {
        refreshCurrentBatteryInfo()
        checkOngoingSession()
        startRealTimeUpdates()
    }

    private fun startRealTimeUpdates() {
        isRealTimeUpdateActive = true
        viewModelScope.launch {
            while (isRealTimeUpdateActive) {
                refreshCurrentBatteryInfo()
                delay(3000)
            }
        }
    }

    fun stopRealTimeUpdates() {
        isRealTimeUpdateActive = false
    }

    fun loadBatteryHealth() {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val result = calculateHealthUseCase()
                _batteryHealthResult.value = result

                if (result == null) {
                    Timber.d("No battery health data available yet")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to calculate battery health")
                _errorMessage.value = "배터리 Health 계산 실패: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadDeviceSpec() {
        viewModelScope.launch {
            try {
                val spec = getDeviceSpecUseCase()
                _deviceSpec.value = spec
                Timber.d("Device spec loaded: ${spec.deviceModel}, ${spec.designCapacity} mAh")
            } catch (e: Exception) {
                Timber.e(e, "Failed to load device spec")
                _errorMessage.value = "기기 정보 로드 실패: ${e.message}"
            }
        }
    }

    fun refreshCurrentBatteryInfo() {
        val info = BatteryUtils.getCurrentBatteryInfo(getApplication())
        _currentBatteryInfo.value = info
    }

    fun startMonitoring() {
        try {
            startMonitoringUseCase()
            _isMonitoring.value = true
            Timber.i("Charging monitoring started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start monitoring")
            _errorMessage.value = "모니터링 시작 실패: ${e.message}"
        }
    }

    /**
     * 방전 모니터링 시작
     */
    fun startDischargeMonitoring() {
        try {
            val context = getApplication<Application>() as Context
            val intent = Intent(context, BatteryMonitoringService::class.java).apply {
                action = BatteryMonitoringService.ACTION_START_DISCHARGE_MONITORING
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            _isDischargeMonitoring.value = true
            Timber.i("Discharge monitoring started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start discharge monitoring")
            _errorMessage.value = "방전 모니터링 시작 실패: ${e.message}"
        }
    }

    /**
     * 방전 모니터링 중지
     */
    fun stopDischargeMonitoring() {
        try {
            val context = getApplication<Application>() as Context
            val intent = Intent(context, BatteryMonitoringService::class.java).apply {
                action = BatteryMonitoringService.ACTION_STOP_DISCHARGE_MONITORING
            }
            context.startService(intent)

            _isDischargeMonitoring.value = false
            Timber.i("Discharge monitoring stopped")
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop discharge monitoring")
            _errorMessage.value = "방전 모니터링 중지 실패: ${e.message}"
        }
    }

    fun clearAllData() {
        viewModelScope.launch {
            try {
                sessionRepository.deleteAllSessions()
                _batteryHealthResult.value = null
                Timber.i("All data cleared")
            } catch (e: Exception) {
                Timber.e(e, "Failed to clear data")
                _errorMessage.value = "데이터 삭제 실패: ${e.message}"
            }
        }
    }

    private fun checkOngoingSession() {
        viewModelScope.launch {
            try {
                val ongoingSession = sessionRepository.getOngoingSession()
                _isMonitoring.value = ongoingSession != null
            } catch (e: Exception) {
                Timber.e(e, "Failed to check ongoing session")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopRealTimeUpdates()
    }
}