// data/remote/BatterySpecApi.kt
package com.batteryhealth.monitor.data.remote

import com.batteryhealth.monitor.data.remote.dto.CrowdsourcedDataResponse
import com.batteryhealth.monitor.data.remote.dto.CrowdsourcedSubmission
import com.batteryhealth.monitor.data.remote.dto.DeviceSpecResponse
import com.batteryhealth.monitor.data.remote.dto.SubmitResponse
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * 배터리 스펙 온라인 API 인터페이스
 *
 * 실제 사용 시 자체 백엔드 서버 또는 서드파티 API를 구현해야 함
 * 예시: https://api.batteryhealth.example.com/v1/
 */
interface BatterySpecApi {

    /**
     * 기기 모델로 배터리 스펙 검색
     *
     * @param query 검색어 (제조사 + 모델명)
     * @param limit 최대 결과 개수
     * @return 검색된 기기 스펙 리스트
     *
     * 예시 요청: GET /devices/search?query=Samsung+SM-S928N&limit=5
     */
    @GET("devices/search")
    suspend fun searchDevice(
        @Query("query") query: String,
        @Query("limit") limit: Int = 10
    ): List<DeviceSpecResponse>

    /**
     * 정확한 모델명으로 배터리 스펙 조회
     *
     * @param model 기기 모델명 (예: SM-S928N)
     * @param manufacturer 제조사 (예: Samsung)
     * @return 기기 스펙 또는 null
     *
     * 예시 요청: GET /devices/exact?model=SM-S928N&manufacturer=Samsung
     */
    @GET("devices/exact")
    suspend fun getDeviceByModel(
        @Query("model") model: String,
        @Query("manufacturer") manufacturer: String
    ): DeviceSpecResponse?

    /**
     * 크라우드소싱 데이터 조회
     *
     * @param fingerprint 기기 고유 식별자 (익명화)
     * @return 크라우드소싱 데이터 (평균 용량, 샘플 수 등)
     *
     * 예시 요청: GET /crowdsourced/device?fingerprint=samsung-sm-s928n-b0q
     */
    @GET("crowdsourced/device")
    suspend fun getCrowdsourcedData(
        @Query("fingerprint") fingerprint: String
    ): CrowdsourcedDataResponse

    /**
     * 크라우드소싱 데이터 제출 (사용자 동의 시)
     *
     * @param data 제출할 배터리 측정 데이터
     * @return 제출 결과
     *
     * 예시 요청: POST /crowdsourced/submit
     * Body: { "device_fingerprint": "...", "estimated_capacity": 3850, ... }
     */
    @POST("crowdsourced/submit")
    suspend fun submitCrowdsourcedData(
        @Body data: CrowdsourcedSubmission
    ): SubmitResponse

    /**
     * 특정 제조사의 모든 기기 목록 조회
     *
     * @param manufacturer 제조사 이름
     * @return 기기 스펙 리스트
     *
     * 예시 요청: GET /devices/manufacturer?name=Samsung
     */
    @GET("devices/manufacturer")
    suspend fun getDevicesByManufacturer(
        @Query("name") manufacturer: String
    ): List<DeviceSpecResponse>

    /**
     * 최근 업데이트된 기기 스펙 조회
     *
     * @param since Unix timestamp (milliseconds)
     * @return 업데이트된 기기 스펙 리스트
     *
     * 예시 요청: GET /devices/updates?since=1706428800000
     */
    @GET("devices/updates")
    suspend fun getRecentUpdates(
        @Query("since") since: Long
    ): List<DeviceSpecResponse>

    /**
     * API 상태 확인 (헬스체크)
     *
     * @return 서버 상태 정보
     *
     * 예시 요청: GET /health
     */
    @GET("health")
    suspend fun checkHealth(): Map<String, Any>
}