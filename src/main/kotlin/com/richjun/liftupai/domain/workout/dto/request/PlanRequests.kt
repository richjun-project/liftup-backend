package com.richjun.liftupai.domain.workout.dto.request

import com.fasterxml.jackson.annotation.JsonAlias

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

// 앱(1.1.27 이하)은 이 요청을 camelCase 키(planName·dayName·exerciseId·minReps…)로 보낸다. 전역 매퍼가 SNAKE_CASE 라
// 2026-03-31 기능 추가부터 저장이 전부 400(HttpMessageNotReadable)이었다(2026-10-09 실사용자 21분 작성 후 5회 실패로 발견).
// 두 표기를 모두 받는다 — 이미 설치된 앱도 서버 배포만으로 저장된다.
data class CreateCustomPlanRequest(
    @JsonAlias("planName") val planName: String,
    val days: List<CustomPlanDayRequest>
)

data class CustomPlanDayRequest(
    @JsonAlias("dayName") val dayName: String,
    @JsonAlias("workoutType") val workoutType: String = "FULL_BODY",
    val exercises: List<CustomPlanExerciseRequest>
)

data class CustomPlanExerciseRequest(
    @JsonAlias("exerciseId") val exerciseId: Long,
    val sets: Int = 3,
    @JsonAlias("minReps") val minReps: Int = 8,
    @JsonAlias("maxReps") val maxReps: Int = 12,
    @JsonAlias("restSeconds") val restSeconds: Int = 90
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
