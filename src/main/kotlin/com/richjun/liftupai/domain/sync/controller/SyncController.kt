package com.richjun.liftupai.domain.sync.controller

import com.richjun.liftupai.domain.sync.dto.*
import com.richjun.liftupai.domain.sync.service.SyncService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/sync")
class SyncController(
    private val syncService: SyncService
) {

    @PostMapping("/offline-workouts")
    fun syncOfflineWorkouts(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: OfflineWorkoutSyncRequest
    ): ResponseEntity<ApiResponse<OfflineWorkoutSyncResponse>> {
        val response = syncService.syncOfflineWorkouts(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}