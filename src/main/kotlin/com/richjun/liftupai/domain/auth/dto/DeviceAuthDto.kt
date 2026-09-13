package com.richjun.liftupai.domain.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class InjuryInput(
    @JsonProperty("body_part")
    val bodyPart: String,
    /**
     * 앱의 온보딩은 부위만 고르고 severity 를 보내지 않는다(null 로 옴).
     * 예전엔 non-null 이라 부상을 하나라도 고른 기기 가입이 전부 500 으로 실패했다.
     */
    val severity: String? = null,
) {
    /** 저장/필터에 쓰는 유효 severity. 없으면 보수적으로 moderate. */
    val effectiveSeverity: String get() = severity?.takeIf { it.isNotBlank() } ?: DEFAULT_SEVERITY

    companion object {
        const val DEFAULT_SEVERITY = "moderate"
    }
}

data class DeviceRegisterRequest(
    @field:NotBlank(message = "{validation.device_id.required}")
    @JsonProperty("device_id")
    val deviceId: String,

    @field:NotBlank(message = "{validation.nickname.required}")
    @field:Size(min = 2, max = 20, message = "{validation.nickname.size}")
    val nickname: String,

    @JsonProperty("device_info")
    val deviceInfo: DeviceInfo? = null,

    @JsonProperty("experience_level")
    val experienceLevel: String = "BEGINNER",

    val goals: List<String> = emptyList(),

    @JsonProperty("body_info")
    val bodyInfo: BodyInfoRequest? = null,

    @JsonProperty("pt_style")
    val ptStyle: String = "FRIENDLY",

    @JsonProperty("workout_preferences")
    val workoutPreferences: WorkoutPreferencesRequest? = null,

    val injuries: List<InjuryInput>? = null,

    @JsonProperty("strength_assessment")
    val strengthAssessment: Map<String, Any>? = null,

    @JsonProperty("notification_enabled")
    val notificationEnabled: Boolean = true
)

data class DeviceLoginRequest(
    @field:NotBlank(message = "{validation.device_id.required}")
    @JsonProperty("device_id")
    val deviceId: String
)

data class BodyInfoRequest(
    val height: Double? = null,
    val weight: Double? = null,
    val age: Int? = null,
    val gender: String? = null,
    @JsonProperty("body_fat")
    val bodyFat: Double? = null,
    @JsonProperty("muscle_mass")
    val muscleMass: Double? = null
)

data class WorkoutPreferencesRequest(
    @JsonProperty("weekly_days")
    val weeklyDays: Int = 3,

    @JsonProperty("workout_split")
    val workoutSplit: String = "full_body",

    @JsonProperty("preferred_workout_time")
    val preferredWorkoutTime: String? = null,

    @JsonProperty("workout_duration")
    val workoutDuration: Int = 60,

    @JsonProperty("available_equipment")
    val availableEquipment: List<String> = emptyList()
)

data class DeviceAuthResponse(
    val user: DeviceUserDto,

    @JsonProperty("access_token")
    val accessToken: String,

    @JsonProperty("refresh_token")
    val refreshToken: String
)

data class DeviceUserDto(
    val id: Long,

    @JsonProperty("device_id")
    val deviceId: String,

    val nickname: String,

    @JsonProperty("is_device_account")
    val isDeviceAccount: Boolean = true,

    @JsonProperty("device_registered_at")
    val deviceRegisteredAt: String
)