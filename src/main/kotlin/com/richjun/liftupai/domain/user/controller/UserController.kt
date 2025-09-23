package com.richjun.liftupai.domain.user.controller

import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService
) {

    @GetMapping("/profile")
    fun getProfile(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProfileResponse>> {
        val profile = userService.getProfileV4(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(profile))
    }

    @PostMapping("/profile")
    fun createProfile(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ProfileRequest
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val profile = userService.createProfile(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to "프로필이 생성되었습니다",
            "profile" to profile
        )))
    }

    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ProfileUpdateRequest
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val profile = userService.updateProfileV4(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to "프로필이 업데이트되었습니다",
            "profile" to profile
        )))
    }

    @PostMapping("/onboarding")
    fun completeOnboarding(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: OnboardingRequest
    ): ResponseEntity<ApiResponse<UserProfileResponse>> {
        val profile = userService.completeOnboarding(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(profile))
    }

    @GetMapping("/settings")
    fun getSettings(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<UserSettingsResponse>> {
        val settings = userService.getSettings(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(settings))
    }

    @PutMapping("/settings")
    fun updateSettings(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: UpdateSettingsRequest
    ): ResponseEntity<ApiResponse<UserSettingsResponse>> {
        val settings = userService.updateSettings(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(settings))
    }

    @DeleteMapping("/account")
    fun deactivateAccount(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val result = userService.deactivateAccount(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(result))
    }
}