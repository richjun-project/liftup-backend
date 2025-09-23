package com.richjun.liftupai.domain.ai.controller

import com.richjun.liftupai.domain.ai.dto.*
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/ai")
class AIAnalysisController(
    private val aiAnalysisService: AIAnalysisService
) {

    // 운동 자세 분석
    @PostMapping("/analyze-form")
    fun analyzeForm(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: FormAnalysisRequest
    ): ResponseEntity<ApiResponse<FormAnalysisResponse>> {
        val response = aiAnalysisService.analyzeForm(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // AI 운동 추천 (통합 엔드포인트)
    @GetMapping("/recommendations/workout")
    fun getAIWorkoutRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) duration: Int?,
        @RequestParam(required = false) equipment: String?,
        @RequestParam(required = false, name = "target_muscle") targetMuscle: String?,
        @RequestParam(required = false) difficulty: String?
    ): ResponseEntity<ApiResponse<AIWorkoutRecommendationResponse>> {
        val response = aiAnalysisService.getAIWorkoutRecommendation(
            userDetails.getId(),
            duration,
            equipment,
            targetMuscle,
            difficulty
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // AI 채팅
    @PostMapping("/chat")
    fun chat(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ChatRequest
    ): ResponseEntity<ApiResponse<ChatResponse>> {
        val response = aiAnalysisService.chat(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}