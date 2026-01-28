// domain/usecase/CalculateBatteryHealthUseCase.kt
package com.batteryhealth.monitor.domain.usecase

import com.batteryhealth.monitor.data.repository.ChargingSessionRepository
import com.batteryhealth.monitor.data.repository.DeviceBatterySpecRepository
import com.batteryhealth.monitor.domain.calculator.BatteryHealthCalculator
import com.batteryhealth.monitor.domain.model.BatteryHealthResult
import javax.inject.Inject

class CalculateBatteryHealthUseCase @Inject constructor(
    private val sessionRepository: ChargingSessionRepository,
    private val specRepository: DeviceBatterySpecRepository,
    private val calculator: BatteryHealthCalculator
) {
    suspend operator fun invoke(): BatteryHealthResult? {
        return calculator.calculateBatteryHealth()
    }
}