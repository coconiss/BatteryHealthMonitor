// data/remote/BatterySpecService.kt
package com.batteryhealth.monitor.data.remote

import com.batteryhealth.monitor.data.remote.dto.CrowdsourcedDataResponse
import com.batteryhealth.monitor.data.remote.dto.CrowdsourcedSubmission
import com.batteryhealth.monitor.data.remote.dto.DeviceSpecResponse
import com.batteryhealth.monitor.data.remote.dto.SubmitResponse

/**
 * 배터리 스펙 서비스 인터페이스
 * API와 웹 스크래핑을 조합한 고수준 서비스
 */
interface BatterySpecService {

    /**
     * 기기 검색
     * API 실패 시 자동으로 웹 스크래핑으로 폴백
     */
    suspend fun searchDevice(query: String): List<DeviceSpecResponse>

    /**
     * 크라우드소싱 데이터 조회
     */
    suspend fun getCrowdsourcedData(deviceFingerprint: String): CrowdsourcedDataResponse

    /**
     * 크라우드소싱 데이터 제출
     */
    suspend fun submitCrowdsourcedData(data: CrowdsourcedSubmission): SubmitResponse
}