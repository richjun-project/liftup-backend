package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.workout.dto.request.ApplyTemplateRequest
import com.richjun.liftupai.domain.workout.dto.request.CreateCustomPlanRequest
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
    @PostMapping("/plans/generate-ai")
    fun generateAIPlan(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: GenerateAIPlanRequest
    ): ResponseEntity<Map<String, Any>> {
        val dashboard = aiPlanGenerationService.generateAndApplyPlan(userDetails.getUser(), request)
        return ResponseEntity.ok(mapOf("success" to true, "data" to dashboard))
    }

    // --- AI Plan Generation (SSE 스트리밍) ---
    @PostMapping("/plans/generate-ai/stream")
    fun generateAIPlanStream(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: GenerateAIPlanRequest
    ): org.springframework.web.servlet.mvc.method.annotation.SseEmitter {
        val emitter = org.springframework.web.servlet.mvc.method.annotation.SseEmitter(180_000L)
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()

        // Security context를 Thread 밖에서 미리 가져옴
        val user = userDetails.getUser()

        Thread {
            try {
                val dashboard = aiPlanGenerationService.generateAndApplyPlanWithProgress(
                    user,
                    request
                ) { step, message ->
                    try {
                        val progressJson = mapper.writeValueAsString(mapOf("step" to step, "message" to message))
                        emitter.send(
                            org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                                .event()
                                .name("progress")
                                .data(progressJson, org.springframework.http.MediaType.APPLICATION_JSON)
                        )
                    } catch (_: Exception) {}
                }

                val completeJson = mapper.writeValueAsString(mapOf("success" to true, "data" to dashboard))
                emitter.send(
                    org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                        .event()
                        .name("complete")
                        .data(completeJson, org.springframework.http.MediaType.APPLICATION_JSON)
                )
                emitter.complete()
            } catch (e: Exception) {
                try {
                    val errorJson = mapper.writeValueAsString(mapOf("message" to (e.message ?: "Unknown error")))
                    emitter.send(
                        org.springframework.web.servlet.mvc.method.annotation.SseEmitter
                            .event()
                            .name("error")
                            .data(errorJson, org.springframework.http.MediaType.APPLICATION_JSON)
                    )
                    emitter.complete()
                } catch (_: Exception) {
                    emitter.completeWithError(e)
                }
            }
        }.start()

        return emitter
    }
}
