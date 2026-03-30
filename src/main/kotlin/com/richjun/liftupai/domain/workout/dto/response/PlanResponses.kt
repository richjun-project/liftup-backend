package com.richjun.liftupai.domain.workout.dto.response

data class TemplateSummaryResponse(
    val code: String,
    val name: String,
    val description: String?,
    val targetGoal: String,
    val targetExperience: String,
    val splitType: String,
    val totalDays: Int,
    val estimatedWeeks: Int,
    val iconName: String?,
    val isPremium: Boolean,
    val sourceType: String
)

data class TemplateDetailResponse(
    val code: String,
    val name: String,
    val description: String?,
    val targetGoal: String,
    val targetExperience: String,
    val splitType: String,
    val totalDays: Int,
    val estimatedWeeks: Int,
    val days: List<TemplateDayResponse>
)

data class TemplateDayResponse(
    val dayNumber: Int,
    val dayName: String,
    val workoutType: String,
    val estimatedDurationMinutes: Int,
    val exercises: List<TemplateDayExerciseResponse>
)

data class TemplateDayExerciseResponse(
    val exerciseId: Long,
    val exerciseName: String,
    val imageUrl: String?,
    val orderInDay: Int,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val isCompound: Boolean,
    val notes: String?
)

data class PlanDashboardResponse(
    val planId: Long,
    val planName: String,
    val planDescription: String?,
    val splitType: String,
    val sourceType: String,
    val totalDays: Int,
    val currentDay: Int,
    val progressionModel: String,
    val deloadEveryNWeeks: Int,
    val days: List<PlanDayOverview>,
    val totalWorkoutsCompleted: Int,
    val aiCoachingNotes: String?,
    val createdAt: String
)

data class PlanDayOverview(
    val dayNumber: Int,
    val dayName: String,
    val workoutType: String,
    val estimatedDuration: Int,
    val exerciseCount: Int,
    val totalCompletions: Int,
    val lastCompletedAt: String?,
    val isCurrent: Boolean
)

data class DayWorkoutResponse(
    val dayNumber: Int,
    val dayName: String,
    val workoutType: String,
    val estimatedDuration: Int,
    val completionCount: Int,
    val exercises: List<DayExerciseDetail>,
    val weekNumber: Int = 1,
    val isDeloadWeek: Boolean = false,
    val periodizationPhase: String = "LINEAR_PROGRESSION",
    val readinessScore: ReadinessDto? = null,
    val weeklyVolume: List<VolumeStatusDto> = emptyList()
)

data class DayExerciseDetail(
    val exerciseId: Long,
    val exerciseName: String,
    val imageUrl: String?,
    val sets: Int,
    val minReps: Int,
    val maxReps: Int,
    val restSeconds: Int,
    val isCompound: Boolean,
    val targetRPE: Double,
    val suggestedWeight: Double?,
    val warmupSets: List<WarmupSetDto> = emptyList(),
    val notes: String?,
    val substitutes: List<SubstituteExerciseDto> = emptyList()
)

data class WarmupSetDto(
    val weight: Double,
    val reps: Int
)

data class PlanOptionsResponse(
    val systemTemplates: List<TemplateSummaryResponse>,
    val myAIPlans: List<TemplateSummaryResponse>,
    val canUseAIPlan: Boolean,
    val currentPlan: PlanDashboardResponse?
)

data class ReadinessDto(
    val score: Double,
    val intensityMultiplier: Double,
    val factors: List<String>
)

data class VolumeStatusDto(
    val muscleGroup: String,
    val currentSets: Int,
    val mevSets: Int,
    val mavSets: Int,
    val status: String  // BELOW_MEV, ON_TARGET, ABOVE_MAV
)

data class SubstituteExerciseDto(
    val exerciseId: Long,
    val exerciseName: String,
    val imageUrl: String?,
    val reason: String  // EQUIPMENT, INJURY, PREFERENCE, EQUIVALENT
)
