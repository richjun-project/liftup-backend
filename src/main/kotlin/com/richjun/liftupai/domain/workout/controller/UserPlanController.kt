package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.CreateCustomPlanRequest
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.dto.request.RecommendTemplateRequest
import com.richjun.liftupai.domain.workout.dto.response.TemplateRecommendationResponse
import com.richjun.liftupai.domain.workout.service.TemplateRecommendationService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.domain.workout.dto.request.SetCurrentDayRequest
import com.richjun.liftupai.domain.workout.service.AIPlanGenerationService
import com.richjun.liftupai.domain.workout.service.TemplateService
import com.richjun.liftupai.domain.workout.service.UserPlanService
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2")
class UserPlanController(
    private val userPlanService: UserPlanService,
    private val templateService: TemplateService,
    private val aiPlanGenerationService: AIPlanGenerationService,
    private val templateRecommendationService: TemplateRecommendationService
) {
    // --- Templates ---
    @GetMapping("/templates")
    fun getTemplates(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false, defaultValue = "en") locale: String
    ): ResponseEntity<Map<String, Any>> {
        val templates = templateService.getAllTemplates(userDetails.getId(), locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to templates))
    }

    @GetMapping("/templates/{code}")
    fun getTemplateDetail(
        @PathVariable code: String,
        @RequestParam(required = false, defaultValue = "ko") locale: String
    ): ResponseEntity<Map<String, Any>> {
        val detail = templateService.getTemplateDetail(code, locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to detail))
    }

    /** 온보딩 답변(경험·목표·일수·시간·장비·부상·집중 부위)으로 가장 맞는 프리셋 템플릿을 고른다 */
    @PostMapping("/templates/recommend")
    fun recommendTemplate(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: RecommendTemplateRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<TemplateRecommendationResponse>> {
        validateRecommendRequest(request, locale)
        val response = templateRecommendationService.recommend(userDetails.getUser(), request, locale)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/templates/{code}/apply")
    fun applyTemplate(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable code: String,
        @RequestBody(required = false) request: ApplyTemplateRequest?,
        @RequestParam(required = false, defaultValue = "en") locale: String
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = userPlanService.applyTemplate(userDetails.getUser(), code, request, locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }

    // --- Plan Management ---
    @GetMapping("/plans/active")
    fun getActivePlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false, defaultValue = "en") locale: String
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = userPlanService.getPlanDashboard(userDetails.getId(), locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }

    @GetMapping("/plans/active/days/{dayNumber}")
    fun getDayWorkout(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable dayNumber: Int,
        @RequestParam(required = false) readiness: Int? = null,
        @RequestParam(required = false, defaultValue = "ko") locale: String
    ): ResponseEntity<Map<String, Any>> {
        val workout = userPlanService.getDayWorkout(userDetails.getId(), dayNumber, readiness, locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to workout))
    }

    @PutMapping("/plans/active/days/{dayNumber}/complete")
    fun completeDay(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable dayNumber: Int,
        @RequestParam(required = false, defaultValue = "en") locale: String
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.completeDay(userDetails.getId(), dayNumber)
        val dashboard = userPlanService.getPlanDashboard(userDetails.getId(), locale)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard, "message" to "Day completed"))
    }

    @PutMapping("/plans/active/current-day")
    fun setCurrentDay(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: SetCurrentDayRequest
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.setCurrentDay(userDetails.getId(), request)
        return ResponseEntity.ok(mapOf("success" to true, "message" to "Current day updated"))
    }

    @PutMapping("/plans/active/pause")
    fun pausePlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.pausePlan(userDetails.getId())
        return ResponseEntity.ok(mapOf("success" to true, "message" to "Plan paused"))
    }

    @PutMapping("/plans/active/resume")
    fun resumePlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.resumePlan(userDetails.getId())
        return ResponseEntity.ok(mapOf("success" to true, "message" to "Plan resumed"))
    }

    @DeleteMapping("/plans/active")
    fun abandonPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.abandonActivePlan(userDetails.getId())
        return ResponseEntity.ok(mapOf("success" to true, "message" to "Plan abandoned"))
    }

    @GetMapping("/plans/options")
    fun getPlanOptions(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        val options = userPlanService.getPlanOptions(userDetails.getId())
        return ResponseEntity.ok(mapOf("success" to true, "data" to options))
    }

    // --- Custom Plan ---
    @PostMapping("/plans/custom")
    fun createCustomPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: CreateCustomPlanRequest
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = userPlanService.createCustomPlan(userDetails.getUser(), request)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }

    // --- AI Plan Generation (기존 동기 방식 유지) ---
    // locale은 다른 엔드포인트와 동일하게 전역 LocaleInterceptor가 붙이는 쿼리 파라미터로 받는다.
    @PostMapping("/plans/generate-ai")
    fun generateAIPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: GenerateAIPlanRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<Map<String, Any>> {
        val effectiveRequest = if (locale != null && request.locale == null) {
            request.copy(locale = locale)
        } else {
            request
        }
        val dashboard = aiPlanGenerationService.generateAndApplyPlan(userDetails.getUser(), effectiveRequest)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }


    /**
     * 온보딩 답변 검증 — 목록 길이·값 범위를 제한해 한 요청이 SQL 수천 개(부상 목록 증폭)나 큰 메모리를 만들지 못하게 한다.
     * 알 수 없는 장비·경험 값은 서비스가 안전한 기본값으로 처리하므로 여기서는 형식과 크기만 본다.
     */
    private fun validateRecommendRequest(request: RecommendTemplateRequest, localeParam: String?) {
        // JSON 의 null 원소([null])는 코틀린 타입상 막히지 않으므로 여기서 걸러낸다
        @Suppress("UNCHECKED_CAST")
        fun items(list: List<String>?) = list as List<String?>?
        ExerciseRequestValidator.list(items(request.goals), "goals", ExerciseRequestValidator.MAX_GOALS)
        ExerciseRequestValidator.list(items(request.equipment), "equipment", ExerciseRequestValidator.MAX_EQUIPMENT)
        ExerciseRequestValidator.list(items(request.injuries), "injuries", ExerciseRequestValidator.MAX_INJURIES)
        ExerciseRequestValidator.list(items(request.focusAreas), "focus_areas", ExerciseRequestValidator.MAX_FOCUS_AREAS)
        ExerciseRequestValidator.range(request.weeklyDays, "weekly_days", 1, 7)
        ExerciseRequestValidator.range(request.sessionDuration, "session_duration", 5, 300)
        ExerciseRequestValidator.range(request.age, "age", 5, 120)
        ExerciseRequestValidator.text(request.experienceLevel, "experience_level")
        ExerciseRequestValidator.text(request.gender, "gender")
        ExerciseRequestValidator.text(request.trainingStyle, "training_style")
        ExerciseRequestValidator.locale(request.locale)
        ExerciseRequestValidator.locale(localeParam)
    }
}
