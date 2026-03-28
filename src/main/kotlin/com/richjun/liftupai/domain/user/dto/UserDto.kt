package com.richjun.liftupai.domain.user.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.global.time.AppTime
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
    @field:Min(50, message = "{validation.height_min}")
    @field:Max(300, message = "{validation.height_max}")
    val height: Double?,

    @field:Min(20, message = "{validation.weight_min}")
    @field:Max(500, message = "{validation.weight_max}")
    val weight: Double?,

    @field:Min(3, message = "{validation.body_fat_min}")
    @field:Max(60, message = "{validation.body_fat_max}")
    @JsonProperty("body_fat")
    val bodyFat: Double?,

    @field:Min(10, message = "Muscle mass must be at least 10kg")
    @field:Max(200, message = "Muscle mass must be 200kg or less")
    @JsonProperty("muscle_mass")
    val muscleMass: Double?,

    @field:Min(13, message = "Age must be at least 13")
    @field:Max(100, message = "Age must be 100 or less")
    val age: Int?,

    val gender: String?
)

data class OnboardingRequest(
    @field:NotBlank(message = "{validation.nickname_required}")
    val nickname: String,

    @field:NotBlank(message = "Experience level is required")
    @JsonProperty("experience_level")
    val experienceLevel: String,

    @field:NotNull(message = "Goals are required")
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

    @JsonProperty("time_zone")
    val timeZone: String? = null,

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
    @JsonProperty("time_zone")
    val timeZone: String = AppTime.DEFAULT_TIME_ZONE,
    val units: String = "METRIC"
)

data class UpdateSettingsRequest(
    val notifications: NotificationSettings?,
    val privacy: PrivacySettings?,
    val app: AppSettings?
)
