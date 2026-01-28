// domain/model/ConfidenceLevel.kt
package com.batteryhealth.monitor.domain.model

enum class ConfidenceLevel {
    VERY_LOW,    // 1개 세션 또는 추정 스펙
    LOW,         // 2개 세션
    MEDIUM,      // 3-4개 세션
    HIGH,        // 5-9개 세션
    VERY_HIGH;   // 10개 이상 세션

    fun getDescription(): String {
        return when (this) {
            VERY_LOW -> "매우 낮음 (참고용)"
            LOW -> "낮음"
            MEDIUM -> "보통"
            HIGH -> "높음"
            VERY_HIGH -> "매우 높음"
        }
    }

    fun getColorResource(): Int {
        return when (this) {
            VERY_LOW -> android.R.color.holo_red_dark
            LOW -> android.R.color.holo_orange_dark
            MEDIUM -> android.R.color.holo_blue_dark
            HIGH -> android.R.color.holo_green_dark
            VERY_HIGH -> android.R.color.holo_green_light
        }
    }
}