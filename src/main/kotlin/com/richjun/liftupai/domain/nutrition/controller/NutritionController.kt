package com.richjun.liftupai.domain.nutrition.controller

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.upload.dto.ImageUploadResponse
import com.richjun.liftupai.domain.nutrition.service.NutritionService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/nutrition")
class NutritionController(
    private val nutritionService: NutritionService
) {

    // 영양 기록 조회
    @GetMapping("/history")
    fun getNutritionHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam date: String,
        @RequestParam(defaultValue = "week") period: String
    ): ResponseEntity<ApiResponse<NutritionHistoryResponse>> {
        val response = nutritionService.getNutritionHistory(userDetails.getId(), date, period)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 영양 기록 저장
    @PostMapping("/log")
    fun logNutrition(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: NutritionLogRequest
    ): ResponseEntity<ApiResponse<NutritionLogResponse>> {
        val response = nutritionService.logNutrition(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 식단 분석
    @PostMapping("/analyze-meal")
    fun analyzeMeal(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: MealAnalysisRequest
    ): ResponseEntity<ApiResponse<MealAnalysisResponse>> {
        val response = nutritionService.analyzeMeal(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 식단 이미지 업로드
    @PostMapping("/upload-image")
    fun uploadMealImage(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam("image") file: MultipartFile
    ): ResponseEntity<ApiResponse<ImageUploadResponse>> {
        val response = nutritionService.uploadMealImage(userDetails.getId(), file)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    // 하루 식단 추천
    @GetMapping("/recommendations/daily")
    fun getDailyMealPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<DailyMealPlanResponse>> {
        val response = nutritionService.getDailyMealPlan(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 오늘 영양 진행률 단일 조회 (목표/섭취/잔여 + 매크로 + 끼니별 + 운동 소모 칼로리)
    @GetMapping("/today-summary")
    fun getTodaySummary(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<TodayNutritionSummaryResponse>> {
        val response = nutritionService.getTodaySummary(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 식단 기록 수정 (끼니 타입/칼로리/매크로)
    @PutMapping("/log/{id}")
    fun updateMealLog(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable id: Long,
        @RequestBody request: UpdateMealLogRequest
    ): ResponseEntity<ApiResponse<NutritionLogResponse>> {
        val response = nutritionService.updateMealLog(userDetails.getId(), id, request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 식단 기록 삭제
    @DeleteMapping("/log/{id}")
    fun deleteMealLog(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable id: Long
    ): ResponseEntity<ApiResponse<NutritionLogResponse>> {
        val response = nutritionService.deleteMealLog(userDetails.getId(), id)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}