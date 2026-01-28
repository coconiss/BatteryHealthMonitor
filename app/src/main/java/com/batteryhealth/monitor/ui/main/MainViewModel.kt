// ui/main/MainViewModel.kt 수정
package com.batteryhealth.monitor.ui.main

import android.app.Application
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

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage

    // 실시간 업데이트 활성화 여부
    private var isRealTimeUpdateActive = false

    init {
        refreshCurrentBatteryInfo()
        checkOngoingSession()
        startRealTimeUpdates()
    }

    /**
     * 3초마다 배터리 정보 자동 갱신
     */
    private fun startRealTimeUpdates() {
        isRealTimeUpdateActive = true
        viewModelScope.launch {
            while (isRealTimeUpdateActive) {
                refreshCurrentBatteryInfo()
                delay(3000) // 3초 간격
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
            Timber.i("Monitoring started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start monitoring")
            _errorMessage.value = "모니터링 시작 실패: ${e.message}"
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