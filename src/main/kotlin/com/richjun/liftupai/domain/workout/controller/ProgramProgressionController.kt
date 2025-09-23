package com.richjun.liftupai.domain.workout.controller

import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.ProgramProgressionService
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.user.service.UserService
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
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgramProgressionAnalysis>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val analysis = programProgressionService.analyzeProgression(user)
        return ResponseEntity.ok(ApiResponse.success(analysis))
    }

    @GetMapping("/volume/optimization")
    fun getVolumeOptimization(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<VolumeOptimizationRecommendation>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val recommendation = programProgressionService.optimizeVolume(user)
        return ResponseEntity.ok(ApiResponse.success(recommendation))
    }

    @GetMapping("/recovery")
    fun analyzeRecovery(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<RecoveryAnalysis>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val analysis = programProgressionService.analyzeRecovery(user)
        return ResponseEntity.ok(ApiResponse.success(analysis))
    }

    @GetMapping("/transition/check")
    fun checkProgramTransition(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgramTransitionRecommendation>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val recommendation = programProgressionService.checkProgramTransition(user)
        return ResponseEntity.ok(ApiResponse.success(recommendation))
    }

    @GetMapping("/summary")
    fun getProgressionSummary(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<ProgressionSummary>> {
        val user = userRepository.findById(userDetails.getId())
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 진급 분석 데이터를 기반으로 요약 생성
        val analysis = programProgressionService.analyzeProgression(user)

        val summary = ProgressionSummary(
            currentLevel = "${analysis.currentProgram} - 주 ${analysis.currentDaysPerWeek}회",
            nextMilestone = analysis.recommendation?.newProgram
                ?: "현재 프로그램 ${3 - analysis.completedCycles}사이클 더 완료",
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
        @RequestBody request: ApplyProgressionRequest
    ): ResponseEntity<ApiResponse<ApplyProgressionResponse>> {
        val userId = userDetails.getId()
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 사용자 프로필 업데이트
        userService.updateWorkoutProgram(userId, request.newProgram, request.newDaysPerWeek)

        return ResponseEntity.ok(ApiResponse.success(
            ApplyProgressionResponse(
                success = true,
                message = "프로그램이 ${request.newProgram}(주 ${request.newDaysPerWeek}회)로 업그레이드되었습니다",
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