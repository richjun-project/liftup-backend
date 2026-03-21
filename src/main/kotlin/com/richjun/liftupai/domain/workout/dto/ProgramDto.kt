package com.richjun.liftupai.domain.workout.dto

// Response DTOs
data class ProgramListResponse(
    val programs: List<ProgramSummary>
)

data class ProgramSummary(
    val code: String,
    val name: String,
    val splitType: String,
    val experienceLevel: String,
    val goal: String,
    val daysPerWeek: Int,
    val durationWeeks: Int,
    val progressionModel: String,
    val description: String?
)

data class ProgramDetailResponse(
    val code: String,
    val name: String,
    val splitType: String,
    val experienceLevel: String,
    val goal: String,
    val daysPerWeek: Int,
    val durationWeeks: Int,
    val progressionModel: String,
    val deloadEveryNWeeks: Int,
    val description: String?,
    val days: List<ProgramDayDetail>
)

data class ProgramDayDetail(
    val dayNumber: Int,
    val name: String,
    val workoutType: String,
    val estimatedDuration: Int,
    val exercises: List<ProgramExerciseDetail>
)

data class ProgramExerciseDetail(
    val exerciseId: Long,
    val name: String,
    val order: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("is_compound") val isCompound: Boolean,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("target_rpe") val targetRPE: Double,
    @com.fasterxml.jackson.annotation.JsonProperty("is_optional") val isOptional: Boolean,
    val notes: String?
)

data class EnrollRequest(
    val programCode: String,
    val injuries: List<InjuryInput>? = null
)

data class InjuryInput(
    val bodyPart: String,   // "shoulder", "knee", "lower_back", "wrist", "elbow"
    val severity: String    // "MILD", "MODERATE", "SEVERE"
)

data class EnrollmentStatusResponse(
    val programCode: String,
    val programName: String,
    val currentWeek: Int,
    val currentDay: Int,
    val totalCompletedWorkouts: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("is_deload_week") val isDeloadWeek: Boolean,
    val status: String,
    val startDate: String,
    val lastActiveDate: String?
)

data class TodayWorkoutResponse(
    val programName: String,
    val weekNumber: Int,
    val dayNumber: Int,
    val dayName: String,
    @com.fasterxml.jackson.annotation.JsonProperty("is_deload_week") val isDeloadWeek: Boolean,
    val periodizationPhase: String,
    val workoutType: String,
    val estimatedDuration: Int,
    val exercises: List<TodayExerciseResponse>,
    val graduationStatus: GraduationStatusDto? = null,
    val weeklyVolume: List<WeeklyVolumeStatusDto>? = null,
    val readinessScore: ReadinessScoreDto? = null
)

data class TodayExerciseResponse(
    val exerciseId: Long,
    val name: String,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val suggestedWeight: Double?,
    @com.fasterxml.jackson.annotation.JsonProperty("target_rpe") val targetRPE: Double,
    @com.fasterxml.jackson.annotation.JsonProperty("is_compound") val isCompound: Boolean,
    val warmupSets: List<WarmupSetResponse>,
    val substitutes: List<SubstituteResponse>
)

data class WarmupSetResponse(val weight: Double, val reps: Int)
data class SubstituteResponse(val exerciseId: Long, val name: String, val reason: String)
data class WeeklyVolumeStatusDto(
    val muscleGroup: String,
    val currentSets: Int,
    val mevSets: Int,
    val mavSets: Int,
    val status: String
)

data class ReadinessScoreDto(
    val score: Double,
    val factors: List<String>,
    val intensityMultiplier: Double
)

data class WeeklyScheduleResponse(
    val programName: String,
    val currentWeek: Int,
    @com.fasterxml.jackson.annotation.JsonProperty("is_deload_week") val isDeloadWeek: Boolean,
    val days: List<ScheduleDayResponse>
)

data class ScheduleDayResponse(
    val dayNumber: Int,
    val name: String,
    val workoutType: String,
    @com.fasterxml.jackson.annotation.JsonProperty("is_completed") val isCompleted: Boolean,
    @com.fasterxml.jackson.annotation.JsonProperty("is_today") val isToday: Boolean
)

data class ExerciseOverrideRequest(
    val originalExerciseId: Long,
    val substituteExerciseId: Long,
    val reason: String = "PREFERENCE"
)

data class SubstituteListResponse(
    val exerciseId: Long,
    val exerciseName: String,
    val substitutes: List<SubstituteResponse>
)
