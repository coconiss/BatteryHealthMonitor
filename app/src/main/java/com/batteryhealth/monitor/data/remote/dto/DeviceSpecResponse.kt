package com.batteryhealth.monitor.data.remote.dto

import com.google.gson.annotations.SerializedName

data class DeviceSpecResponse(
    /**
     * 기기 이름
     */
    @SerializedName("device_name")
    val deviceName: String,

    /**
     * 배터리 용량 (mAh)
     */
    @SerializedName("battery_capacity")
    val batteryCapacity: Int,

    /**
     * 데이터 출처
     * 예: "gsmarena", "official", "user_manual"
     */
    @SerializedName("source")
    val source: String,

    /**
     * 검증된 데이터 여부
     */
    @SerializedName("verified")
    val verified: Boolean,

    /**
     * 신뢰도 (0.0 ~ 1.0)
     */
    @SerializedName("confidence")
    val confidence: Float,

    /**
     * 기기 모델 코드들
     * 예: ["SM-S928N", "SM-S928B", "SM-S928U"]
     */
    @SerializedName("model_codes")
    val modelCodes: List<String>? = null,

    /**
     * 제조사
     */
    @SerializedName("manufacturer")
    val manufacturer: String? = null,

    /**
     * 출시 연도
     */
    @SerializedName("release_year")
    val releaseYear: Int? = null,

    /**
     * 추가 정보 (배터리 타입, 탈착 가능 여부 등)
     */
    @SerializedName("additional_info")
    val additionalInfo: Map<String, String>? = null
)