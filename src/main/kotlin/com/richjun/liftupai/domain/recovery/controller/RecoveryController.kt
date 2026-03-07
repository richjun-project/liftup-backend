package com.richjun.liftupai.domain.recovery.controller

import com.richjun.liftupai.domain.recovery.dto.*
import com.richjun.liftupai.domain.recovery.service.RecoveryService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/recovery")
class RecoveryController(
    private val recoveryService: RecoveryService
) {

    @GetMapping("/status")
    fun getRecoveryStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RecoveryStatusResponse>> {
        val response = recoveryService.getRecoveryStatus(userDetails.getId(), locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PutMapping("/update")
    fun updateRecovery(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: UpdateRecoveryRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<UpdateRecoveryResponse>> {
        val response = recoveryService.updateRecovery(userDetails.getId(), request, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/recommendations")
    fun getRecoveryRecommendations(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RecoveryRecommendationsResponse>> {
        val response = recoveryService.getRecoveryRecommendations(userDetails.getId(), locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/activity")
    fun recordRecoveryActivity(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: RecordActivityRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RecordActivityResponse>> {
        val response = recoveryService.recordActivity(userDetails.getId(), request, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/history")
    fun getRecoveryHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam startDate: String,
        @RequestParam endDate: String,
        @RequestParam(required = false, defaultValue = "all") activityType: String?,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RecoveryHistoryResponse>> {
        val response = recoveryService.getRecoveryHistory(
            userDetails.getId(),
            startDate,
            endDate,
            activityType,
            locale
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
