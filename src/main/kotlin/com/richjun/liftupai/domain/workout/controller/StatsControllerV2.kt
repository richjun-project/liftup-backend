package com.richjun.liftupai.domain.workout.controller

import com.richjun.liftupai.domain.stats.dto.*
import com.richjun.liftupai.domain.stats.service.StatsService
import com.richjun.liftupai.domain.workout.dto.*
import com.richjun.liftupai.domain.workout.service.WorkoutServiceV2
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v2/stats")
class StatsControllerV2(
    private val workoutServiceV2: WorkoutServiceV2,
    private val workoutPlanService: com.richjun.liftupai.domain.workout.service.WorkoutPlanService,
    private val statsService: StatsService,
    private val recoveryService: com.richjun.liftupai.domain.recovery.service.RecoveryService
) {

    @GetMapping("/workout-completion")
    fun getWorkoutCompletionStats(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) sessionId: Long?
    ): ResponseEntity<ApiResponse<WorkoutCompletionStats>> {
        val response = workoutServiceV2.getWorkoutCompletionStats(userDetails.getId(), sessionId)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/calendar")
    fun getWorkoutCalendar(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam year: Int,
        @RequestParam month: Int
    ): ResponseEntity<ApiResponse<WorkoutCalendarResponse>> {
        val response = workoutServiceV2.getWorkoutCalendar(userDetails.getId(), year, month)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // === WorkoutControllerV4에서 이동된 메서드 ===

    // 주간 운동 통계
    @GetMapping("/weekly")
    fun getWeeklyStats(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<com.richjun.liftupai.domain.user.dto.WeeklyStatsResponse>> {
        val stats = workoutPlanService.getWeeklyStats(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(stats))
    }

    // === StatsController에서 이동된 메서드 ===

    @GetMapping("/overview")
    fun getOverview(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "week") period: String
    ): ResponseEntity<ApiResponse<StatsOverviewResponse>> {
        val response = statsService.getOverview(userDetails.getId(), period)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/volume")
    fun getVolumeStats(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "week") period: String,
        @RequestParam(required = false) startDate: String?
    ): ResponseEntity<ApiResponse<VolumeStatsResponse>> {
        val response = statsService.getVolumeStats(userDetails.getId(), period, startDate)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/muscle-distribution")
    fun getMuscleDistribution(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "month") period: String
    ): ResponseEntity<ApiResponse<MuscleDistributionResponse>> {
        val response = statsService.getMuscleDistribution(userDetails.getId(), period)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/personal-records")
    fun getPersonalRecords(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<PersonalRecordsResponse>> {
        val response = statsService.getPersonalRecords(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/progress")
    fun getProgress(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "weight") metric: String,
        @RequestParam(defaultValue = "3months") period: String
    ): ResponseEntity<ApiResponse<ProgressResponse>> {
        val response = statsService.getProgress(userDetails.getId(), metric, period)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 근육 회복도 조회
    @GetMapping("/muscle-recovery")
    fun getMuscleRecovery(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<com.richjun.liftupai.domain.recovery.dto.RecoveryStatusResponse>> {
        val response = recoveryService.getRecoveryStatus(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    // 통계 대시보드 (모든 통계 통합)
    @GetMapping("/dashboard")
    fun getStatsDashboard(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "week") period: String
    ): ResponseEntity<ApiResponse<StatsDashboardResponse>> {
        val overview = statsService.getOverview(userDetails.getId(), period)
        val volumeStats = statsService.getVolumeStats(userDetails.getId(), period, null)
        val muscleDistribution = statsService.getMuscleDistribution(userDetails.getId(), period)
        val recovery = recoveryService.getRecoveryStatus(userDetails.getId())

        val dashboard = StatsDashboardResponse(
            overview = overview,
            volumeStats = volumeStats,
            muscleDistribution = muscleDistribution,
            muscleRecovery = recovery
        )

        return ResponseEntity.ok(ApiResponse.success(dashboard))
    }
}

// 대시보드 통합 응답 DTO
data class StatsDashboardResponse(
    val overview: StatsOverviewResponse,
    val volumeStats: VolumeStatsResponse,
    val muscleDistribution: MuscleDistributionResponse,
    val muscleRecovery: com.richjun.liftupai.domain.recovery.dto.RecoveryStatusResponse
)