package com.richjun.liftupai.domain.social.controller

import com.richjun.liftupai.domain.social.dto.*
import com.richjun.liftupai.domain.social.service.SocialService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/social")
class SocialController(
    private val socialService: SocialService
) {

    @PostMapping("/share-workout")
    fun shareWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ShareWorkoutRequest
    ): ResponseEntity<ApiResponse<ShareWorkoutResponse>> {
        val response = socialService.shareWorkout(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @GetMapping("/find-partners")
    fun findPartners(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) gymLocation: String?,
        @RequestParam(required = false) workoutTime: String?,
        @RequestParam(required = false) level: String?
    ): ResponseEntity<ApiResponse<FindPartnersResponse>> {
        val response = socialService.findPartners(userDetails.getId(), gymLocation, workoutTime, level)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}