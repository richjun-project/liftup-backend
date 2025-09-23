package com.richjun.liftupai.domain.user.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

// Response DTOs
data class UserProfileResponse(
    @JsonProperty("user_id")
    val userId: Long,
    val email: String,
    val nickname: String,

    @JsonProperty("experience_level")
    val experienceLevel: String,

    @JsonProperty("join_date")
    val joinDate: String,

    @JsonProperty("body_info")
    val bodyInfo: BodyInfoDto?,

    val goals: List<String>,

    @JsonProperty("pt_style")
    val ptStyle: String,

    val subscription: SubscriptionDto
)

data class BodyInfoDto(
    val height: Double?,
    val weight: Double?,

    @JsonProperty("body_fat")
    val bodyFat: Double?,

    @JsonProperty("muscle_mass")
    val muscleMass: Double?,

    val age: Int?,
    val gender: String?
)

data class SubscriptionDto(
    val plan: String = "FREE",
    val status: String = "ACTIVE",

    @JsonProperty("expiry_date")
    val expiryDate: String? = null
)

// Request DTOs
data class UpdateProfileRequest(
    val nickname: String?,

    @JsonProperty("body_info")
    val bodyInfo: BodyInfoUpdateDto?,

    val goals: List<String>?,

    @JsonProperty("pt_style")
    val ptStyle: String?
)

data class BodyInfoUpdateDto(
    @field:Min(50, message = "키는 50cm 이상이어야 합니다")
    @field:Max(300, message = "키는 300cm 이하여야 합니다")
    val height: Double?,

    @field:Min(20, message = "몸무게는 20kg 이상이어야 합니다")
    @field:Max(500, message = "몸무게는 500kg 이하여야 합니다")
    val weight: Double?,

    @field:Min(3, message = "체지방률은 3% 이상이어야 합니다")
    @field:Max(60, message = "체지방률은 60% 이하여야 합니다")
    @JsonProperty("body_fat")
    val bodyFat: Double?,

    @field:Min(10, message = "근육량은 10kg 이상이어야 합니다")
    @field:Max(200, message = "근육량은 200kg 이하여야 합니다")
    @JsonProperty("muscle_mass")
    val muscleMass: Double?,

    @field:Min(13, message = "나이는 13세 이상이어야 합니다")
    @field:Max(100, message = "나이는 100세 이하여야 합니다")
    val age: Int?,

    val gender: String?
)

data class OnboardingRequest(
    @field:NotBlank(message = "닉네임은 필수입니다")
    val nickname: String,

    @field:NotBlank(message = "경험 수준은 필수입니다")
    @JsonProperty("experience_level")
    val experienceLevel: String,

    @field:NotNull(message = "목표는 필수입니다")
    val goals: List<String>,

    @JsonProperty("body_info")
    val bodyInfo: BodyInfoUpdateDto?,

    @JsonProperty("pt_style")
    val ptStyle: String = "FRIENDLY",

    @JsonProperty("notification_enabled")
    val notificationEnabled: Boolean = true,

    @JsonProperty("weekly_workout_days")
    val weeklyWorkoutDays: Int?,

    @JsonProperty("workout_split")
    val workoutSplit: String?,

    @JsonProperty("available_equipment")
    val availableEquipment: List<String>?,

    @JsonProperty("preferred_workout_time")
    val preferredWorkoutTime: String?,

    @JsonProperty("workout_duration")
    val workoutDuration: Int?,

    val injuries: List<String>?
)

// Settings DTOs
data class UserSettingsResponse(
    val notifications: NotificationSettings,
    val privacy: PrivacySettings,
    val app: AppSettings
)

data class NotificationSettings(
    @JsonProperty("workout_reminder")
    val workoutReminder: Boolean = true,

    @JsonProperty("ai_messages")
    val aiMessages: Boolean = true,

    val achievements: Boolean = true,
    val marketing: Boolean = false
)

data class PrivacySettings(
    @JsonProperty("share_progress")
    val shareProgress: Boolean = false,

    @JsonProperty("public_profile")
    val publicProfile: Boolean = false
)

data class AppSettings(
    val theme: String = "LIGHT",
    val language: String = "ko",
    val units: String = "METRIC"
)

data class UpdateSettingsRequest(
    val notifications: NotificationSettings?,
    val privacy: PrivacySettings?,
    val app: AppSettings?
)