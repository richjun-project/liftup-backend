package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
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
    private val aiPlanGenerationService: AIPlanGenerationService
) {
    // --- Templates ---
    @GetMapping("/templates")
    fun getTemplates(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        val templates = templateService.getAllTemplates(userDetails.getId())
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

    @PostMapping("/templates/{code}/apply")
    fun applyTemplate(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @PathVariable code: String,
        @RequestBody(required = false) request: ApplyTemplateRequest?
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = userPlanService.applyTemplate(userDetails.getUser(), code, request)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }

    // --- Plan Management ---
    @GetMapping("/plans/active")
    fun getActivePlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = userPlanService.getPlanDashboard(userDetails.getId())
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
        @PathVariable dayNumber: Int
    ): ResponseEntity<Map<String, Any>> {
        userPlanService.completeDay(userDetails.getId(), dayNumber)
        val dashboard = userPlanService.getPlanDashboard(userDetails.getId())
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

    // --- AI Plan Generation ---
    @PostMapping("/plans/generate-ai")
    fun generateAIPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: GenerateAIPlanRequest
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = aiPlanGenerationService.generateAndApplyPlan(userDetails.getUser(), request)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }
}
