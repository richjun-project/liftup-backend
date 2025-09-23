package com.richjun.liftupai.domain.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class DeviceRegisterRequest(
    @field:NotBlank(message = "디바이스 ID는 필수입니다")
    @JsonProperty("device_id")
    val deviceId: String,

    @field:NotBlank(message = "닉네임은 필수입니다")
    @field:Size(min = 2, max = 20, message = "닉네임은 2-20자 사이여야 합니다")
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
    val workoutPreferences: WorkoutPreferencesRequest? = null
)

data class DeviceLoginRequest(
    @field:NotBlank(message = "디바이스 ID는 필수입니다")
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