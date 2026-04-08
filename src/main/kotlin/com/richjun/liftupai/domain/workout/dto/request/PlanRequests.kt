package com.richjun.liftupai.domain.workout.dto.request

data class ApplyTemplateRequest(
    val injuries: List<String>? = null,
    val equipment: List<String>? = null
)

data class SetCurrentDayRequest(
    val dayNumber: Int
)

data class CreateCustomPlanRequest(
    val planName: String,
    val days: List<CustomPlanDayRequest>
)

data class CustomPlanDayRequest(
    val dayName: String,
    val workoutType: String = "FULL_BODY",
    val exercises: List<CustomPlanExerciseRequest>
)

data class CustomPlanExerciseRequest(
    val exerciseId: Long,
    val sets: Int = 3,
    val minReps: Int = 8,
    val maxReps: Int = 12,
    val restSeconds: Int = 90
)

data class GenerateAIPlanRequest(
    val experienceLevel: String,
    val goals: List<String>,
    val gender: String?,
    val age: Int?,
    val weeklyDays: Int,
    val sessionDuration: Int,
    val equipment: List<String>,
    val injuries: List<String>? = null,
    val focusAreas: List<String>? = null,
    val trainingStyle: String? = null
)
