package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty
import com.richjun.liftupai.domain.workout.service.RecoveryStatus
import java.time.LocalDateTime

/**
 * 프로그램 진급 분석 응답
 */
data class ProgramProgressionAnalysis(
    @JsonProperty("current_program")
    val currentProgram: String,

    @JsonProperty("current_days_per_week")
    val currentDaysPerWeek: Int,

    @JsonProperty("completed_cycles")
    val completedCycles: Int,

    @JsonProperty("current_cycle")
    val currentCycle: Int = 1,

    val recommendation: ProgressionRecommendation?,

    @JsonProperty("performance_metrics")
    val performanceMetrics: PerformanceMetrics,

    @JsonProperty("ready_for_progression")
    val readyForProgression: Boolean,

    @JsonProperty("consistency_rate")
    val consistencyRate: Double = 0.0,

    @JsonProperty("recovery_status")
    val recoveryStatus: RecoveryStatus = RecoveryStatus.MODERATE
)

/**
 * 진급 추천 정보
 */
data class ProgressionRecommendation(
    @JsonProperty("new_program")
    val newProgram: String,

    @JsonProperty("new_days_per_week")
    val newDaysPerWeek: Int,

    val reason: String,

    @JsonProperty("expected_benefits")
    val expectedBenefits: List<String>
)

/**
 * 퍼포먼스 지표
 */
data class PerformanceMetrics(
    @JsonProperty("volume_increase_percent")
    val volumeIncreasePercent: Int = 0,

    @JsonProperty("strength_gain_percent")
    val strengthGainPercent: Int = 0,

    @JsonProperty("average_workout_duration")
    val averageWorkoutDuration: Int = 0,

    @JsonProperty("total_workouts")
    val totalWorkouts: Int = 0
)

/**
 * 볼륨 최적화 추천
 */
data class VolumeOptimizationRecommendation(
    @JsonProperty("current_volume")
    val currentVolume: VolumeMetrics,

    @JsonProperty("recommended_volume")
    val recommendedVolume: VolumeMetrics,

    @JsonProperty("adjustment_reason")
    val adjustmentReason: String,

    @JsonProperty("muscle_group_volumes")
    val muscleGroupVolumes: Map<String, Int>,

    @JsonProperty("mev_reached")
    val mevReached: Boolean = false,

    @JsonProperty("mav_exceeded")
    val mavExceeded: Boolean = false
)

/**
 * 볼륨 지표
 */
data class VolumeMetrics(
    @JsonProperty("weekly_volume")
    val weeklyVolume: Double = 0.0,

    @JsonProperty("sets_per_week")
    val setsPerWeek: Int = 0,

    @JsonProperty("reps_per_week")
    val repsPerWeek: Int = 0
)

/**
 * 회복 분석
 */
data class RecoveryAnalysis(
    @JsonProperty("muscle_groups")
    val muscleGroups: Map<String, MuscleRecoveryStatusProgression>,

    @JsonProperty("overall_recovery_score")
    val overallRecoveryScore: Int,

    @JsonProperty("needs_deload_week")
    val needsDeloadWeek: Boolean,

    @JsonProperty("deload_reason")
    val deloadReason: String? = null,

    @JsonProperty("next_recommended_muscles")
    val nextRecommendedMuscles: List<String>
)

/**
 * 근육군별 회복 상태 (진급 분석용)
 */
data class MuscleRecoveryStatusProgression(
    @JsonProperty("muscle_name")
    val muscleName: String,

    @JsonProperty("last_workout")
    val lastWorkout: LocalDateTime,

    @JsonProperty("hours_since_workout")
    val hoursSinceWorkout: Int,

    @JsonProperty("recovery_percentage")
    val recoveryPercentage: Int,

    @JsonProperty("ready_for_next_session")
    val readyForNextSession: Boolean,

    @JsonProperty("recommended_rest_hours")
    val recommendedRestHours: Int
)

/**
 * 프로그램 전환 추천
 */
data class ProgramTransitionRecommendation(
    @JsonProperty("should_transition")
    val shouldTransition: Boolean,

    @JsonProperty("current_program_weeks")
    val currentProgramWeeks: Int,

    @JsonProperty("plateau_detected")
    val plateauDetected: Boolean = false,

    val reason: String,

    @JsonProperty("suggested_programs")
    val suggestedPrograms: List<ProgramSuggestion>,

    @JsonProperty("goal_completion_rate")
    val goalCompletionRate: Int = 0
)

/**
 * 프로그램 제안
 */
data class ProgramSuggestion(
    @JsonProperty("program_name")
    val programName: String,

    @JsonProperty("days_per_week")
    val daysPerWeek: Int,

    val description: String,

    val benefits: List<String>,

    val difficulty: String
)

/**
 * 볼륨 추세
 */
enum class VolumeTrend {
    INCREASING,
    STABLE,
    DECREASING
}

/**
 * 진급 상태 요약 (대시보드용)
 */
data class ProgressionSummary(
    @JsonProperty("current_level")
    val currentLevel: String,

    @JsonProperty("next_milestone")
    val nextMilestone: String,

    @JsonProperty("progress_percentage")
    val progressPercentage: Int,

    @JsonProperty("days_until_progression")
    val daysUntilProgression: Int?,

    @JsonProperty("recent_achievements")
    val recentAchievements: List<ProgressionAchievement>
)

/**
 * 성취 기록 (진급용)
 */
data class ProgressionAchievement(
    val type: String, // "VOLUME_RECORD", "STRENGTH_PR", "CONSISTENCY_STREAK"
    val description: String,
    @JsonProperty("achieved_at")
    val achievedAt: LocalDateTime,
    val value: String
)