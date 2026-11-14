package com.richjun.liftupai.domain.user.controller

import com.richjun.liftupai.domain.user.dto.*
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.i18n.ErrorLocalization
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserController(
    private val userService: UserService,
    private val userPlanService: com.richjun.liftupai.domain.workout.service.UserPlanService,
    private val equipmentPlanFit: com.richjun.liftupai.domain.workout.service.EquipmentPlanFitService,
    private val userRepository: com.richjun.liftupai.domain.auth.repository.UserRepository,
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
        @Valid @RequestBody request: ProfileRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val profile = userService.createProfile(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to ErrorLocalization.message("profile.created", locale ?: "en"),
            "profile" to profile
        )))
    }

    @PutMapping("/profile")
    fun updateProfile(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ProfileUpdateRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val userId = userDetails.getId()
        val equipmentBefore = request.availableEquipment?.let { userPlanService.currentEquipment(userId) }
        val profile = userService.updateProfileV4(userId, request)
        // 장비를 바꿨으면 활성 플랜도 그 장비에 맞춘다(2026-11-14 — 304 가 '운동 플랜 설정'에서 맨몸으로 바꿨는데 플랜은 헬스장 그대로였다).
        // 플랜 맞춤이 실패해도 프로필 저장은 성공으로 돌려준다.
        if (equipmentBefore != null && userPlanService.currentEquipment(userId) != equipmentBefore) {
            runCatching { equipmentPlanFit.fitToCurrentEquipment(userRepository.findById(userId).orElseThrow(), locale ?: "en") }
                .onFailure { org.slf4j.LoggerFactory.getLogger(UserController::class.java).warn("plan refit after equipment change failed user={}: {}", userId, it.message) }
        }
        return ResponseEntity.ok(ApiResponse.success(mapOf(
            "message" to ErrorLocalization.message("profile.updated", locale ?: "en"),
            "profile" to profile
        )))
    }

    @PostMapping("/onboarding")
    fun completeOnboarding(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: OnboardingRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<UserProfileResponse>> {
        val profile = userService.completeOnboarding(userDetails.getId(), request, locale)
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
        @Valid @RequestBody request: UpdateSettingsRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<UserSettingsResponse>> {
        val settings = userService.updateSettings(userDetails.getId(), request)
        // 앱은 실행할 때마다 언어를 여기로 동기화한다 — 그때 저장된 장비와 안 맞는 템플릿 플랜을 바로잡는다(실패해도 설정 저장은 성공)
        runCatching {
            userRepository.findById(userDetails.getId()).orElse(null)?.let { equipmentPlanFit.fitIfStaleTemplatePlan(it, locale ?: settings.app?.language ?: "en") }
        }.onFailure { org.slf4j.LoggerFactory.getLogger(UserController::class.java).warn("stale plan fit on settings sync failed user={}: {}", userDetails.getId(), it.message) }
        return ResponseEntity.ok(ApiResponse.success(settings))
    }

    @DeleteMapping("/account")
    fun deleteAccount(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val result = userService.deleteAccount(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(result))
    }
}