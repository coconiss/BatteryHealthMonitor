// ui/history/SessionDetailViewModel.kt (새 파일)
package com.batteryhealth.monitor.ui.history

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.batteryhealth.monitor.data.local.entity.BatteryMeasurement
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    private val sessionRepository: ChargingSessionRepository
) : ViewModel() {

    private val _session = MutableLiveData<ChargingSession?>()
    val session: LiveData<ChargingSession?> = _session

    private val _measurements = MutableLiveData<List<BatteryMeasurement>>()
    val measurements: LiveData<List<BatteryMeasurement>> = _measurements

    fun loadSessionDetail(sessionId: Long) {
        viewModelScope.launch {
            try {
                _session.value = sessionRepository.getSessionById(sessionId)
                _measurements.value = sessionRepository.getMeasurementsBySession(sessionId)
            } catch (e: Exception) {
                Timber.e(e, "Failed to load session detail")
            }
        }
    }
}