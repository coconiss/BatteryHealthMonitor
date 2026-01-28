// domain/usecase/GetDeviceSpecUseCase.kt
package com.batteryhealth.monitor.domain.usecase

import com.batteryhealth.monitor.data.local.entity.DeviceBatterySpec
import com.batteryhealth.monitor.data.repository.DeviceBatterySpecRepository
import javax.inject.Inject

class GetDeviceSpecUseCase @Inject constructor(
    private val repository: DeviceBatterySpecRepository
) {
    suspend operator fun invoke(): DeviceBatterySpec {
        return repository.getDeviceSpec()
    }
}