package com.richjun.liftupai.domain.ai.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * AI 운동 추천 전용 DTO
 * AI가 생성한 모든 정보를 포함
 */
data class AIWorkoutRecommendationResponse(
    val recommendation: AIWorkoutDetail,
    val alternatives: List<AIAlternativeWorkout> = emptyList(),

    @JsonProperty("ai_insights")
    val aiInsights: AIWorkoutInsights? = null
)

data class AIWorkoutDetail(
    @JsonProperty("workout_id")
    val workoutId: String,

    val name: String,
    val duration: Int,
    val difficulty: String,
    val exercises: List<AIExerciseDetail>,

    @JsonProperty("estimated_calories")
    val estimatedCalories: Int,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String>,

    val equipment: List<String>,

    // AI가 생성한 추가 정보
    val tips: List<String> = emptyList(),

    @JsonProperty("progression_note")
    val progressionNote: String? = null,

    @JsonProperty("coaching_message")
    val coachingMessage: String? = null,

    @JsonProperty("workout_focus")
    val workoutFocus: String? = null
)

data class AIExerciseDetail(
    @JsonProperty("exercise_id")
    val exerciseId: String,

    val name: String,
    val sets: Int,
    val reps: String,
    val rest: Int,
    val order: Int,

    @JsonProperty("suggested_weight")
    val suggestedWeight: Double? = null,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String> = emptyList(),  // DB에서 가져올 수 있는 타겟 근육

    @JsonProperty("equipment_needed")
    val equipmentNeeded: String? = null,  // DB에서 가져올 수 있는 필요 장비

    @JsonProperty("difficulty_level")
    val difficultyLevel: String? = null  // DB에서 가져올 수 있는 운동 난이도
)

data class AIAlternativeWorkout(
    @JsonProperty("workout_id")
    val workoutId: String,

    val name: String,
    val duration: Int,
    val reason: String? = null,  // 대체 운동 추천 이유

    @JsonProperty("target_focus")
    val targetFocus: String? = null  // 대체 운동의 초점
)

/**
 * AI 운동 분석 및 인사이트 (간소화)
 */
data class AIWorkoutInsights(
    @JsonProperty("workout_rationale")
    val workoutRationale: String? = null,  // 이 운동 구성의 핵심 이유

    @JsonProperty("key_point")
    val keyPoint: String? = null,  // 오늘 운동의 핵심 포인트

    @JsonProperty("next_step")
    val nextStep: String? = null  // 다음 단계 제안
)

/**
 * AI 운동 시작 요청/응답
 */
data class StartAIWorkoutRequest(
    @JsonProperty("workout_id")
    val workoutId: String,

    val adjustments: AIWorkoutAdjustments = AIWorkoutAdjustments()
)

data class AIWorkoutAdjustments(
    val duration: Int? = null,

    @JsonProperty("skip_exercises")
    val skipExercises: List<String> = emptyList(),

    @JsonProperty("substitute_exercises")
    val substituteExercises: Map<String, String> = emptyMap(),

    @JsonProperty("intensity_adjustment")
    val intensityAdjustment: String? = null  // "easier", "harder", "same"
)

data class StartAIWorkoutResponse(
    @JsonProperty("session_id")
    val sessionId: String,

    @JsonProperty("workout_name")
    val workoutName: String,

    @JsonProperty("start_time")
    val startTime: String,

    val exercises: List<AIWorkoutExercise>,

    @JsonProperty("estimated_duration")
    val estimatedDuration: Int,

    @JsonProperty("ai_coach_message")
    val aiCoachMessage: String? = null,

    val started: Boolean
)

data class AIWorkoutExercise(
    @JsonProperty("exercise_id")
    val exerciseId: String,

    val name: String,

    @JsonProperty("planned_sets")
    val plannedSets: Int,

    @JsonProperty("planned_reps")
    val plannedReps: String,

    @JsonProperty("suggested_weight")
    val suggestedWeight: Double,

    @JsonProperty("rest_timer")
    val restTimer: Int,

    // AI 코칭 정보
    @JsonProperty("pre_exercise_tip")
    val preExerciseTip: String? = null,

    @JsonProperty("form_reminders")
    val formReminders: List<String> = emptyList(),

    @JsonProperty("breathing_pattern")
    val breathingPattern: String? = null
)