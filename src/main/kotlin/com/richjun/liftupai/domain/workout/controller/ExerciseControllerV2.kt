package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
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
        @RequestParam(defaultValue = "false") hasGif: Boolean,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) search: String?
    ): ResponseEntity<*> {
        // page/size 파라미터가 있으면 페이징 응답
        if (page != null) {
            val pageSize = (size ?: 20).coerceIn(1, 100)
            val pageable = PageRequest.of(page, pageSize, Sort.by("name"))
            val pagedResult = workoutServiceV2.getExercisesV2Paged(
                category, equipment, hasGif, locale, search, pageable
            )
            return ResponseEntity.ok(ApiResponse.success(pagedResult))
        }

        // 기존 전체 조회 (하위 호환)
        val response = workoutServiceV2.getExercisesV2(category, equipment, hasGif, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/{exerciseId}/details")
    fun getExerciseDetailsV2(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ExerciseDetailResponseV2>> {
        val response = workoutServiceV2.getExerciseDetailsV2(userDetails.getId(), exerciseId, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
