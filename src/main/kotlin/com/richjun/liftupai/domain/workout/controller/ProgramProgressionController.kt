package com.richjun.liftupai.domain.workout.controller

import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.ProgramProgressionService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.service.UserService
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/progression")
class ProgramProgressionController(
    private val programProgressionService: ProgramProgressionService,
    private val userRepository: UserRepository,
    private val userService: UserService
) {

    @GetMapping("/analysis")
    fun analyzeProgression(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ProgramProgressionAnalysis>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("User not found") }

        val analysis = programProgressionService.analyzeProgression(user, locale)
        return ResponseEntity.ok(ApiResponse.success(analysis))
    }

    @GetMapping("/volume/optimization")
    fun getVolumeOptimization(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<VolumeOptimizationRecommendation>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("User not found") }

        val recommendation = programProgressionService.optimizeVolume(user, locale)
        return ResponseEntity.ok(ApiResponse.success(recommendation))
    }

    @GetMapping("/recovery")
    fun analyzeRecovery(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<RecoveryAnalysis>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("User not found") }

        val analysis = programProgressionService.analyzeRecovery(user, locale)
        return ResponseEntity.ok(ApiResponse.success(analysis))
    }

    @GetMapping("/transition/check")
    fun checkProgramTransition(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ProgramTransitionRecommendation>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("User not found") }

        val recommendation = programProgressionService.checkProgramTransition(user, locale)
        return ResponseEntity.ok(ApiResponse.success(recommendation))
    }

    @GetMapping("/summary")
    fun getProgressionSummary(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ProgressionSummary>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("User not found") }
        val normalizedLocale = WorkoutLocalization.normalizeLocale(locale)

        val analysis = programProgressionService.analyzeProgression(user, normalizedLocale)
        val currentProgramName = WorkoutLocalization.splitName(analysis.currentProgram, normalizedLocale)
        val nextMilestone = analysis.recommendation?.newProgram
            ?.let { WorkoutLocalization.splitName(it, normalizedLocale) }
            ?: WorkoutLocalization.message(
                "progression.summary.next_milestone.remaining_cycles",
                normalizedLocale,
                3 - analysis.completedCycles
            )

        val summary = ProgressionSummary(
            currentLevel = WorkoutLocalization.message(
                "progression.summary.current_level",
                normalizedLocale,
                currentProgramName,
                analysis.currentDaysPerWeek
            ),
            nextMilestone = nextMilestone,
            progressPercentage = minOf(
                (analysis.completedCycles * 100 / 3),
                (analysis.consistencyRate.toInt())
            ),
            daysUntilProgression = if (analysis.readyForProgression) 0
                else (3 - analysis.completedCycles) * analysis.currentDaysPerWeek * 7,
            recentAchievements = listOf()
        )

        return ResponseEntity.ok(ApiResponse.success(summary))
    }

    @PostMapping("/apply-recommendation")
    fun applyProgressionRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestBody request: ApplyProgressionRequest,
        @RequestParam(required = false) locale: String?
    ): ResponseEntity<ApiResponse<ApplyProgressionResponse>> {
        val userId = userDetails.getId()
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val normalizedLocale = WorkoutLocalization.normalizeLocale(locale)

        userService.updateWorkoutProgram(userId, request.newProgram, request.newDaysPerWeek)
        val programName = WorkoutLocalization.splitName(request.newProgram, normalizedLocale)

        return ResponseEntity.ok(ApiResponse.success(
            ApplyProgressionResponse(
                success = true,
                message = WorkoutLocalization.message(
                    "progression.apply.success",
                    normalizedLocale,
                    programName,
                    request.newDaysPerWeek
                ),
                newProgram = request.newProgram,
                newDaysPerWeek = request.newDaysPerWeek
            ))
        )
    }
}

/**
 * 진급 적용 요청
 */
data class ApplyProgressionRequest(
    @JsonProperty("new_program")
    val newProgram: String,

    @JsonProperty("new_days_per_week")
    val newDaysPerWeek: Int
)

/**
 * 진급 적용 응답
 */
data class ApplyProgressionResponse(
    val success: Boolean,
    val message: String,

    @JsonProperty("new_program")
    val newProgram: String,

    @JsonProperty("new_days_per_week")
    val newDaysPerWeek: Int
)
