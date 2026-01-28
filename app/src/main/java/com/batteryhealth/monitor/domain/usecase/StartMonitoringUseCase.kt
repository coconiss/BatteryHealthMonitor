// domain/usecase/StartMonitoringUseCase.kt
package com.batteryhealth.monitor.domain.usecase

import android.content.Context
import android.content.Intent
import android.os.Build
import com.batteryhealth.monitor.service.BatteryMonitoringService
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class StartMonitoringUseCase @Inject constructor(
    @ApplicationContext private val context: Context
) {
    operator fun invoke() {
        val intent = Intent(context, BatteryMonitoringService::class.java).apply {
            action = BatteryMonitoringService.ACTION_START_MONITORING
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}