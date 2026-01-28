// ui/history/HistoryViewModel.kt
package com.batteryhealth.monitor.ui.history

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import com.batteryhealth.monitor.data.local.entity.ChargingSession
import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val sessionRepository: ChargingSessionRepository
) : ViewModel() {

    val sessions: LiveData<List<ChargingSession>> =
        sessionRepository.getAllSessionsFlow().asLiveData()
}