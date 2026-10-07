package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.ExerciseCatalogDetailService
import com.richjun.liftupai.domain.workout.service.ExerciseSuggestionService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import com.richjun.liftupai.domain.workout.favorite.ExerciseFavoriteService
import org.springframework.data.domain.PageRequest
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/exercises")
class ExerciseControllerV2(
    private val exerciseCatalogDetailService: ExerciseCatalogDetailService,
    private val exerciseSuggestionService: ExerciseSuggestionService,
    private val exerciseFavoriteService: ExerciseFavoriteService
) {

    @GetMapping
    fun getExercisesV2(
        @AuthenticationPrincipal userDetails: CustomUserDetails?,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) equipment: String?, // 쉼표 구분 목록 허용 (DUMBBELL,BODYWEIGHT)
        @RequestParam(defaultValue = "false") hasGif: Boolean,
        @RequestParam(required = false) locale: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
        @RequestParam(required = false) search: String?
    ): ResponseEntity<*> {
        ExerciseRequestValidator.query(search)
        ExerciseRequestValidator.locale(locale)
        ExerciseRequestValidator.csv(equipment, "equipment", ExerciseRequestValidator.MAX_EQUIPMENT_TOKENS)
        val pageSize = ExerciseRequestValidator.page(page, size)
        val userId = userDetails?.getId()

        // page 파라미터가 있으면 페이징 응답
        if (page != null) {
            // 정렬은 서비스(검색 엔진/브라우즈 순서)가 결정한다 — 이름순 정렬을 강제하지 않는다
            val pageable = PageRequest.of(page, pageSize)
            val pagedResult = exerciseCatalogDetailService.getExercisesV2Paged(
                category, equipment, hasGif, locale, search, pageable, userId
            )
            return ResponseEntity.ok(ApiResponse.success(pagedResult))
        }

        // 기존 전체 조회 (하위 호환) — 최대 100개까지만. 더 보려면 page/size 를 쓴다.
        val response = exerciseCatalogDetailService.getExercisesV2(category, equipment, hasGif, locale, search, userId)
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
        ExerciseRequestValidator.locale(locale)
        ExerciseRequestValidator.csv(muscleGroups, "muscle_groups", ExerciseRequestValidator.MAX_MUSCLE_TOKENS)
        ExerciseRequestValidator.csv(
            excludeIds, "exclude_ids", ExerciseRequestValidator.MAX_EXCLUDE_IDS, ExerciseRequestValidator.MAX_EXCLUDE_IDS_LENGTH
        )
        ExerciseRequestValidator.text(category, "category", 40)
        val safeLimit = ExerciseRequestValidator.limit(limit, ExerciseSuggestionService.DEFAULT_LIMIT, ExerciseSuggestionService.MAX_LIMIT)
        val items = exerciseSuggestionService.suggest(userDetails.getId(), category, muscleGroups, excludeIds, safeLimit, locale)
        return ResponseEntity.ok(ApiResponse.success(mapOf("items" to items)))
    }

    /** 최근에 한 운동 (기록 기반, 중복 없이 최신순) */
    @GetMapping("/recent")
    fun getRecent(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        ExerciseRequestValidator.locale(locale)
        val safeLimit = ExerciseRequestValidator.limit(limit, ExerciseFavoriteService.DEFAULT_RECENT, ExerciseFavoriteService.MAX_RECENT)
        val items = exerciseFavoriteService.recent(userDetails.getId(), safeLimit, locale)
        return ResponseEntity.ok(ApiResponse.success(mapOf("items" to items)))
    }

    /** 즐겨찾기한 운동 (최근에 추가한 순) */
    @GetMapping("/favorites")
    fun getFavorites(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<Map<String, Any>>> {
        ExerciseRequestValidator.locale(locale)
        val items = exerciseFavoriteService.favorites(userDetails.getId(), locale)
        return ResponseEntity.ok(ApiResponse.success(mapOf("items" to items)))
    }

    @PutMapping("/{exerciseId}/favorite")
    fun addFavorite(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        val favorite = exerciseFavoriteService.add(userDetails.getId(), exerciseId)
        return ResponseEntity.ok(ApiResponse.success(mapOf("favorite" to favorite)))
    }

    @DeleteMapping("/{exerciseId}/favorite")
    fun removeFavorite(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        val favorite = exerciseFavoriteService.remove(userDetails.getId(), exerciseId)
        return ResponseEntity.ok(ApiResponse.success(mapOf("favorite" to favorite)))
    }

    @GetMapping("/{exerciseId}/details")
    fun getExerciseDetailsV2(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable exerciseId: Long,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ExerciseDetailResponseV2>> {
        ExerciseRequestValidator.locale(locale)
        val response = exerciseCatalogDetailService.getExerciseDetailsV2(userDetails.getId(), exerciseId, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}
