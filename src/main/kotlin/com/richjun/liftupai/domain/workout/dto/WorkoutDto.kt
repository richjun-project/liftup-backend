package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.NotBlank

// Request DTOs
data class StartWorkoutRequest(
    @JsonProperty("planned_exercises")
    val plannedExercises: List<PlannedExercise> = emptyList()
)

data class PlannedExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("exercise_name")
    val exerciseName: String? = null,

    // sets를 유연하게 처리 - Int 또는 List<PlannedSet> 모두 허용
    @JsonProperty("sets")
    val sets: Any? = 3,  // Int 또는 List<PlannedSet>

    @JsonProperty("target_reps")
    val reps: Int = 10,

    val weight: Double? = null,

    @JsonProperty("order_index")
    val orderIndex: Int? = null
) {
    // sets 개수를 가져오는 프로퍼티
    val setsCount: Int
        get() = when (sets) {
            is Int -> sets
            is Number -> sets.toInt()  // Double 등 다른 숫자 타입 처리
            is List<*> -> sets.size
            else -> 3  // 기본값
        }

    // 세트 상세 정보 가져오기
    @Suppress("UNCHECKED_CAST")
    val setDetails: List<PlannedSet>?
        get() = when (sets) {
            is List<*> -> {
                try {
                    // List의 각 요소를 PlannedSet으로 변환
                    sets.mapNotNull { item ->
                        when (item) {
                            is PlannedSet -> item
                            is Map<*, *> -> {
                                // Map을 PlannedSet으로 변환
                                @Suppress("UNCHECKED_CAST")
                                val map = item as Map<String, Any>
                                PlannedSet(
                                    setNumber = (map["set_number"] as? Number)?.toInt(),
                                    reps = (map["reps"] as Number).toInt(),
                                    weight = (map["weight"] as? Number)?.toDouble(),
                                    rest = (map["rest"] as? Number)?.toInt() ?: 90
                                )
                            }
                            else -> null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
            else -> null
        }
}

// 계획된 세트 정보
data class PlannedSet(
    @JsonProperty("set_number")
    val setNumber: Int? = null,
    val reps: Int,
    val weight: Double? = null,
    val rest: Int? = 90  // 휴식 시간 (초)
)

data class EndWorkoutRequest(
    val exercises: List<CompletedExercise>,
    val duration: Int,
    val notes: String?
)

data class CompletedExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,
    val sets: List<ExerciseSetDto>
)

data class ExerciseSetDto(
    val weight: Double,
    val reps: Int,
    val rpe: Int? = null,
    @JsonProperty("rest_time")
    val restTime: Int? = null
)

data class UpdateWorkoutRequest(
    val exercises: List<CompletedExercise>?,
    val duration: Int?,
    val notes: String?
)

data class AddSetRequest(
    @JsonProperty("session_id")
    val sessionId: Long,
    val sets: List<ExerciseSetDto>
)

data class GenerateProgramRequest(
    val goals: List<String>,

    @JsonProperty("experience_level")
    val experienceLevel: String,

    @JsonProperty("weekly_workout_days")
    val weeklyWorkoutDays: Int,

    @JsonProperty("workout_split")
    val workoutSplit: String,

    @JsonProperty("available_equipment")
    val availableEquipment: List<String>,

    val duration: Int,
    val injuries: List<String> = emptyList()
)

data class AdjustVolumeRequest(
    @JsonProperty("session_id")
    val sessionId: Long,

    @JsonProperty("fatigue_level")
    val fatigueLevel: Int,

    @JsonProperty("time_available")
    val timeAvailable: Int,

    @JsonProperty("equipment_available")
    val equipmentAvailable: List<String>
)

data class CalculateWeightRequest(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("experience_level")
    val experienceLevel: String,

    @JsonProperty("body_weight")
    val bodyWeight: Double,

    val gender: String,

    @JsonProperty("previous_records")
    val previousRecords: List<PreviousRecord> = emptyList()
)

data class PreviousRecord(
    val weight: Double,
    val reps: Int,
    val date: String
)

data class Estimate1RMRequest(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @field:NotNull
    @field:Min(1)
    val weight: Double,

    @field:NotNull
    @field:Min(1)
    val reps: Int,

    val rpe: Int? = null
)

data class StrengthTestRequest(
    val exercises: List<StrengthTestExercise>
)

data class StrengthTestExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,
    val weight: Double,
    val reps: Int,
    val rpe: Int? = null
)

// Response DTOs
data class StartWorkoutResponse(
    @JsonProperty("session_id")
    val sessionId: Long,

    @JsonProperty("start_time")
    val startTime: String
)

data class WorkoutSummaryResponse(
    val success: Boolean,
    val summary: WorkoutSummary
)

data class WorkoutSummary(
    val duration: Int,

    @JsonProperty("total_volume")
    val totalVolume: Double,

    @JsonProperty("exercises_completed")
    val exercisesCompleted: Int,

    @JsonProperty("calories_burned")
    val caloriesBurned: Int?,

    @JsonProperty("personal_records")
    val personalRecords: List<String> = emptyList()
)

data class WorkoutSessionsResponse(
    val sessions: List<WorkoutSessionDto>,

    @JsonProperty("total_count")
    val totalCount: Long
)

data class WorkoutSessionDto(
    @JsonProperty("session_id")
    val sessionId: Long,

    val date: String,
    val duration: Int?,

    @JsonProperty("total_volume")
    val totalVolume: Double?,

    @JsonProperty("exercise_count")
    val exerciseCount: Int,

    val status: String
)

data class WorkoutDetailResponse(
    @JsonProperty("session_id")
    val sessionId: Long,

    val date: String,
    val duration: Int?,
    val exercises: List<WorkoutExerciseDto>,

    @JsonProperty("total_volume")
    val totalVolume: Double?,

    @JsonProperty("calories_burned")
    val caloriesBurned: Int?
)

data class WorkoutExerciseDto(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("exercise_name")
    val exerciseName: String,

    val sets: List<ExerciseSetDto>,

    @JsonProperty("total_volume")
    val totalVolume: Double
)

data class AddSetResponse(
    val success: Boolean,

    @JsonProperty("total_volume")
    val totalVolume: Double,

    @JsonProperty("is_personal_record")
    val isPersonalRecord: Boolean
)

data class TodayWorkoutRecommendation(
    @JsonProperty("program_name")
    val programName: String?,

    @JsonProperty("day_in_program")
    val dayInProgram: Int?,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String>,

    val exercises: List<RecommendedExercise>,

    @JsonProperty("estimated_duration")
    val estimatedDuration: Int,

    val difficulty: String,

    @JsonProperty("recovery_status")
    val recoveryStatus: List<MuscleRecoveryStatus>,

    val alternatives: List<AlternativeExercise> = emptyList()
)

data class RecommendedExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    val name: String,

    @JsonProperty("recommended_sets")
    val recommendedSets: Int,

    @JsonProperty("recommended_reps")
    val recommendedReps: String,

    @JsonProperty("recommended_weight")
    val recommendedWeight: Double?,

    val rpe: Int?,

    @JsonProperty("rest_time")
    val restTime: Int,

    @JsonProperty("previous_performance")
    val previousPerformance: String?
)

data class MuscleRecoveryStatus(
    val muscle: String,

    @JsonProperty("recovery_percentage")
    val recoveryPercentage: Int,

    @JsonProperty("last_worked")
    val lastWorked: String?
)

data class AlternativeExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,
    val name: String,
    val reason: String
)

data class WorkoutProgramResponse(
    @JsonProperty("program_id")
    val programId: Long,

    val mesocycle: MesocycleInfo,

    @JsonProperty("weekly_schedule")
    val weeklySchedule: List<WeeklyWorkout>
)

data class MesocycleInfo(
    val phase: String,
    val weeks: Int,

    @JsonProperty("focus_areas")
    val focusAreas: List<String>,

    @JsonProperty("volume_progression")
    val volumeProgression: String
)

data class WeeklyWorkout(
    val day: String,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String>,

    val exercises: List<String>
)

data class CurrentProgramResponse(
    @JsonProperty("program_id")
    val programId: Long?,

    @JsonProperty("program_name")
    val programName: String?,

    @JsonProperty("current_week")
    val currentWeek: Int?,

    @JsonProperty("total_weeks")
    val totalWeeks: Int?,

    @JsonProperty("workout_split")
    val workoutSplit: String?,

    @JsonProperty("next_workout")
    val nextWorkout: NextWorkoutInfo?,

    @JsonProperty("weekly_schedule")
    val weeklySchedule: List<WeeklyWorkout>
)

data class NextWorkoutInfo(
    @JsonProperty("day_name")
    val dayName: String,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String>,

    val exercises: List<String>,

    @JsonProperty("estimated_duration")
    val estimatedDuration: Int
)

data class RecoveryStatusResponse(
    @JsonProperty("muscle_groups")
    val muscleGroups: List<MuscleGroupRecovery>,

    @JsonProperty("overall_fatigue")
    val overallFatigue: Int,

    @JsonProperty("deload_recommended")
    val deloadRecommended: Boolean
)

data class MuscleGroupRecovery(
    val name: String,

    @JsonProperty("last_worked")
    val lastWorked: String?,

    @JsonProperty("recovery_percentage")
    val recoveryPercentage: Int,

    @JsonProperty("ready_for_work")
    val readyForWork: Boolean,

    @JsonProperty("estimated_full_recovery")
    val estimatedFullRecovery: String?
)

data class AdjustedVolumeResponse(
    @JsonProperty("adjusted_exercises")
    val adjustedExercises: List<AdjustedExercise>,

    @JsonProperty("volume_multiplier")
    val volumeMultiplier: Double,

    val reason: String
)

data class AdjustedExercise(
    @JsonProperty("exercise_id")
    val exerciseId: Long,
    val name: String,
    val sets: Int,
    val reps: String,
    val weight: Double?
)

data class WeightRecommendation(
    @JsonProperty("recommended_weight")
    val recommendedWeight: Double,

    @JsonProperty("warmup_sets")
    val warmupSets: List<WarmupSet>,

    @JsonProperty("working_sets")
    val workingSets: List<WorkingSet>,

    @JsonProperty("calculation_method")
    val calculationMethod: String,

    val confidence: Double
)

data class WarmupSet(
    val weight: Double,
    val reps: Int
)

data class WorkingSet(
    val weight: Double,
    val reps: Int,
    val rpe: Int?
)

data class OneRMEstimation(
    @JsonProperty("estimated_1rm")
    val estimated1RM: Double,

    val formula: String,
    val percentages: Map<String, Double>
)

data class StrengthTestResult(
    @JsonProperty("estimated_maxes")
    val estimatedMaxes: Map<String, Double>,

    @JsonProperty("strength_level")
    val strengthLevel: String,

    @JsonProperty("strength_score")
    val strengthScore: Double,

    val recommendations: List<String>
)

data class StrengthStandardsResponse(
    val standards: List<StrengthStandard>
)

data class StrengthStandard(
    val exercise: String,
    val beginner: Double,
    val novice: Double,
    val intermediate: Double,
    val advanced: Double,
    val elite: Double
)

data class ExercisesResponse(
    val exercises: List<ExerciseDto>
)

data class ExerciseDto(
    val id: Long,
    val name: String,
    val category: String,

    @JsonProperty("muscle_groups")
    val muscleGroups: List<String>,

    val equipment: String?,
    val instructions: String?
)

data class ExerciseDetailResponse(
    val exercise: ExerciseDto,

    @JsonProperty("personal_records")
    val personalRecords: PersonalRecord?,

    @JsonProperty("last_performed")
    val lastPerformed: String?
)

data class PersonalRecord(
    val weight: Double,
    val reps: Int,
    val date: String
)