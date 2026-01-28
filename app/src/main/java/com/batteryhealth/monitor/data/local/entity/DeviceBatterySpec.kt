// data/local/entity/DeviceBatterySpec.kt
package com.batteryhealth.monitor.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "device_battery_specs")
data class DeviceBatterySpec(
    @PrimaryKey
    val deviceModel: String,

    val manufacturer: String,
    val designCapacity: Int, // mAh

    /**
     * 데이터 소스
     * - "embedded_database": 앱 내장 JSON
     * - "online_api:gsmarena": GSMArena API
     * - "crowdsourced:N": 크라우드소싱 (N개 샘플)
     * - "system_property": 시스템 프로퍼티
     * - "estimated_by_screen_size": 화면 크기 기반 추정
     */
    val source: String,

    /**
     * 신뢰도 (0.0 ~ 1.0)
     * - 1.0: 제조사 공식 스펙
     * - 0.95: 검증된 데이터베이스
     * - 0.85~0.90: 온라인 API
     * - 0.50~0.85: 크라우드소싱
     * - 0.30: 추정값
     */
    val confidence: Float,

    val deviceName: String = "",
    val verified: Boolean = false,
    val lastUpdated: Long = System.currentTimeMillis()
)