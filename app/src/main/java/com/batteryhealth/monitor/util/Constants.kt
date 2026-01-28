// util/Constants.kt
package com.batteryhealth.monitor.util

object Constants {

    // 배터리 임계값
    const val MIN_CHARGE_PERCENTAGE = 10
    const val MAX_SAFE_TEMPERATURE = 40f
    const val MIN_VALID_CAPACITY = 1000 // mAh
    const val MAX_VALID_CAPACITY = 10000 // mAh

    // 신뢰도 임계값
    const val MIN_SESSIONS_FOR_MEDIUM_CONFIDENCE = 3
    const val MIN_SESSIONS_FOR_HIGH_CONFIDENCE = 5
    const val MIN_SESSIONS_FOR_VERY_HIGH_CONFIDENCE = 10

    // 데이터 수집
    const val MONITORING_INTERVAL_SECONDS = 30
    const val BATCH_INSERT_SIZE = 10

    // Health 평가 기준
    const val HEALTH_EXCELLENT = 90f
    const val HEALTH_GOOD = 80f
    const val HEALTH_FAIR = 70f
    const val HEALTH_POOR = 60f

    // Shared Preferences Keys
    const val PREF_NAME = "battery_health_prefs"
    const val PREF_FIRST_LAUNCH = "first_launch"
    const val PREF_AUTO_START_MONITORING = "auto_start_monitoring"
    const val PREF_HIGH_TEMP_WARNING = "high_temp_warning"
}