// data/remote/dto/CrowdsourcedDataResponse.kt
package com.batteryhealth.monitor.data.remote.dto

import com.google.gson.annotations.SerializedName

/**
 * 크라우드소싱 데이터 응답
 * 다른 사용자들이 측정한 동일 기기의 배터리 데이터
 */
data class CrowdsourcedDataResponse(
    /**
     * 기기 이름 (사람이 읽을 수 있는 형태)
     * 예: "Samsung Galaxy S24 Ultra"
     */
    @SerializedName("device_name")
    val deviceName: String?,

    /**
     * 평균 추정 용량 (mAh)
     * 모든 샘플의 평균값
     */
    @SerializedName("average_capacity")
    val averageCapacity: Int,

    /**
     * 샘플 개수
     * 데이터 신뢰도 평가에 사용
     */
    @SerializedName("sample_count")
    val sampleCount: Int,

    /**
     * 표준 편차 (mAh)
     * 데이터 분산도 측정
     */
    @SerializedName("std_deviation")
    val stdDeviation: Float,

    /**
     * 최소 추정 용량 (mAh)
     */
    @SerializedName("min_capacity")
    val minCapacity: Int? = null,

    /**
     * 최대 추정 용량 (mAh)
     */
    @SerializedName("max_capacity")
    val maxCapacity: Int? = null,

    /**
     * 중앙값 (mAh)
     */
    @SerializedName("median_capacity")
    val medianCapacity: Int? = null,

    /**
     * 데이터 마지막 업데이트 시간 (Unix timestamp)
     */
    @SerializedName("last_updated")
    val lastUpdated: Long? = null,

    /**
     * 기기 식별자 (fingerprint)
     */
    @SerializedName("device_fingerprint")
    val deviceFingerprint: String? = null
)

/**
 * 크라우드소싱 데이터 제출 요청
 */
data class CrowdsourcedSubmission(
    /**
     * 기기 고유 식별자 (익명화)
     * 예: "samsung-sm-s928n-b0q"
     */
    @SerializedName("device_fingerprint")
    val deviceFingerprint: String,

    /**
     * 추정된 배터리 용량 (mAh)
     */
    @SerializedName("estimated_capacity")
    val estimatedCapacity: Int,

    /**
     * 측정 신뢰도 (0.0 ~ 1.0)
     */
    @SerializedName("confidence")
    val confidence: Float,

    /**
     * 유효한 충전 세션 수
     */
    @SerializedName("valid_sessions_count")
    val validSessionsCount: Int,

    /**
     * Android 버전
     */
    @SerializedName("android_version")
    val androidVersion: Int,

    /**
     * 기기 모델명
     */
    @SerializedName("device_model")
    val deviceModel: String,

    /**
     * 제조사
     */
    @SerializedName("manufacturer")
    val manufacturer: String,

    /**
     * 제출 시간 (Unix timestamp)
     */
    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    /**
     * 앱 버전
     */
    @SerializedName("app_version")
    val appVersion: String,

    /**
     * CHARGE_COUNTER 지원 여부
     */
    @SerializedName("charge_counter_supported")
    val chargeCounterSupported: Boolean
)

/**
 * 데이터 제출 응답
 */
data class SubmitResponse(
    /**
     * 제출 성공 여부
     */
    @SerializedName("success")
    val success: Boolean,

    /**
     * 응답 메시지
     */
    @SerializedName("message")
    val message: String,

    /**
     * 제출된 데이터 ID
     */
    @SerializedName("submission_id")
    val submissionId: String? = null,

    /**
     * 업데이트된 평균 용량 (mAh)
     */
    @SerializedName("updated_average")
    val updatedAverage: Int? = null,

    /**
     * 현재 총 샘플 수
     */
    @SerializedName("total_samples")
    val totalSamples: Int? = null
)

/**
 * 에러 응답
 */
data class ApiErrorResponse(
    @SerializedName("error")
    val error: String,

    @SerializedName("error_code")
    val errorCode: String? = null,

    @SerializedName("message")
    val message: String? = null,

    @SerializedName("timestamp")
    val timestamp: Long = System.currentTimeMillis()
)