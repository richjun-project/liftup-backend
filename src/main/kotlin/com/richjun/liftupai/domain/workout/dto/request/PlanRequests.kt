package com.richjun.liftupai.domain.workout.dto.request

data class ApplyTemplateRequest(
    val injuries: List<String>? = null,
    val equipment: List<String>? = null,
    // 선택: 보내면 집중 부위 볼륨과 채워 넣는 운동 난이도에 반영된다. 안 보내면 저장된 온보딩 값을 쓴다.
    val experienceLevel: String? = null,
    val goals: List<String>? = null,
    val focusAreas: List<String>? = null,
    // 선택: 안 보내면 저장된 나이·회당 운동 시간을 쓴다(recommend 와 같은 해석)
    val age: Int? = null,
    val sessionDuration: Int? = null
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
    val height: Double? = null,
    val weight: Double? = null,
    val weeklyDays: Int,
    val sessionDuration: Int,
    val equipment: List<String>,
    val injuries: List<String>? = null,
    val focusAreas: List<String>? = null,
    val trainingStyle: String? = null,
    val ptStyle: String? = null,
    val workoutSplit: String? = null,
    val strengthAssessment: Map<String, Any>? = null,
    val estimatedMaxes: Map<String, Double>? = null,
    val workingWeights: Map<String, Double>? = null,
    val strengthLevel: String? = null,
    val locale: String? = null
)

/**
 * POST /api/v2/templates/recommend — 앱 온보딩의 toAIPlanRequest 와 같은 키.
 * 모르는 키(pt_style, workout_split, height …)는 무시한다.
 */
data class RecommendTemplateRequest(
    val experienceLevel: String? = null,
    val goals: List<String>? = null,
    val gender: String? = null,
    val age: Int? = null,
    val weeklyDays: Int? = null,
    val sessionDuration: Int? = null,
    val equipment: List<String>? = null,
    val injuries: List<String>? = null,
    val focusAreas: List<String>? = null,
    val trainingStyle: String? = null,
    val locale: String? = null
)
