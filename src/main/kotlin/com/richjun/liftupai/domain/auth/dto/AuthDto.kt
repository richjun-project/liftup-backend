package com.richjun.liftupai.domain.auth.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.domain.user.dto.BodyInfoDto
import com.richjun.liftupai.domain.user.dto.SubscriptionDto
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다")
    @field:Size(min = 6, message = "비밀번호는 최소 6자 이상이어야 합니다")
    val password: String,

    @field:NotBlank(message = "닉네임은 필수입니다")
    @field:Size(min = 2, max = 20, message = "닉네임은 2-20자 사이여야 합니다")
    val nickname: String,

    @JsonProperty("device_info")
    val deviceInfo: DeviceInfo? = null
)

data class LoginRequest(
    @field:NotBlank(message = "이메일은 필수입니다")
    val email: String,

    @field:NotBlank(message = "비밀번호는 필수입니다")
    val password: String,

    @JsonProperty("device_info")
    val deviceInfo: DeviceInfo? = null
)

data class AuthResponse(
    @JsonProperty("user_id")
    val userId: Long,

    @JsonProperty("access_token")
    val accessToken: String,

    @JsonProperty("refresh_token")
    val refreshToken: String,

    val profile: UserProfileDto? = null
)

data class RefreshTokenRequest(
    @field:NotBlank(message = "Refresh token은 필수입니다")
    @param:JsonProperty("refresh_token")
    val refreshToken: String
)

data class UserProfileDto(
    @JsonProperty("user_id")
    val userId: Long,
    val email: String,
    val nickname: String,
    val level: String,

    @JsonProperty("join_date")
    val joinDate: String,

    @JsonProperty("body_info")
    val bodyInfo: BodyInfoDto? = null,

    val goals: List<String> = emptyList(),

    @JsonProperty("pt_style")
    val ptStyle: String? = null,

    val subscription: SubscriptionDto? = null,

    @JsonProperty("is_device_account")
    val isDeviceAccount: Boolean = false,

    @JsonProperty("device_registered_at")
    val deviceRegisteredAt: String? = null
)

data class DeviceInfo(
    @JsonProperty("device_id")
    val deviceId: String? = null,

    val platform: String? = null, // android, ios

    val model: String? = null,

    val manufacturer: String? = null,

    @JsonProperty("os_version")
    val osVersion: String? = null,

    @JsonProperty("app_version")
    val appVersion: String? = null,

    @JsonProperty("android_version")
    val androidVersion: String? = null,

    @JsonProperty("sdk_int")
    val sdkInt: Int? = null
)

