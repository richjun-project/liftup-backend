package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/exercises")
class ExerciseControllerV2(
    private val workoutServiceV2: WorkoutServiceV2
) {

    @GetMapping
    fun getExercisesV2(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) equipment: String?,
        @RequestParam(defaultValue = "false") hasGif: Boolean
    ): ResponseEntity<ApiResponse<List<ExerciseDetailV2>>> {
        val response = workoutServiceV2.getExercisesV2(category, equipment, hasGif)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/{exerciseId}/details")
    fun getExerciseDetailsV2(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long
    ): ResponseEntity<ApiResponse<ExerciseDetailResponseV2>> {
        val response = workoutServiceV2.getExerciseDetailsV2(userDetails.getId(), exerciseId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}