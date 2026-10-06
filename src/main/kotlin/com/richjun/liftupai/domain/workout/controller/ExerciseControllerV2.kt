package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogDetailService
import com.richjun.liftupai.domain.workout.service.ExerciseSuggestionService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/exercises")
class ExerciseControllerV2(
    private val exerciseCatalogDetailService: ExerciseCatalogDetailService,
    private val exerciseSuggestionService: ExerciseSuggestionService
) {

    @GetMapping
    fun getExercisesV2(
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) equipment: String?, // 쉼표 구분 목록 허용 (DUMBBELL,BODYWEIGHT)
        @RequestParam(defaultValue = "false") hasGif: Boolean,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) search: String?
    ): ResponseEntity<*> {
        // page/size 파라미터가 있으면 페이징 응답
        if (page != null) {
            val pageSize = (size ?: 20).coerceIn(1, 100)
            // 정렬은 서비스(검색 엔진/브라우즈 순서)가 결정한다 — 이름순 정렬을 강제하지 않는다
            val pageable = PageRequest.of(page.coerceAtLeast(0), pageSize)
            val pagedResult = exerciseCatalogDetailService.getExercisesV2Paged(
                category, equipment, hasGif, locale, search, pageable
            )
            return ResponseEntity.ok(ApiResponse.success(pagedResult))
        }

        // 기존 전체 조회 (하위 호환)
        val response = exerciseCatalogDetailService.getExercisesV2(category, equipment, hasGif, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    /**
     * 개인화 운동 추천 — 저장된 보유 장비로 할 수 있고, 부상 제한을 피하고, 경험 수준에 맞는 운동을
     * 부족한 근육(muscle_groups)·집중 부위·티어·인기도 순으로. 각 항목에 reason(현지화된 한 줄)이 붙는다.
     */
    @GetMapping("/suggestions")
    fun getSuggestions(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false, name = "muscle_groups") muscleGroups: String?,
        @RequestParam(required = false, name = "exclude_ids") excludeIds: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        val items = exerciseSuggestionService.suggest(userDetails.getId(), category, muscleGroups, excludeIds, limit, locale)
        return ResponseEntity.ok(ApiResponse.success(mapOf("items" to items)))
    }

    @GetMapping("/{exerciseId}/details")
    fun getExerciseDetailsV2(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ExerciseDetailResponseV2>> {
        val response = exerciseCatalogDetailService.getExerciseDetailsV2(userDetails.getId(), exerciseId, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
