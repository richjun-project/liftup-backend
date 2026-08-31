package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty

// Basic workout recommendation DTOs

// GET /api/v2/workouts/recommendations/basic Response
data class QuickWorkoutRecommendationResponse(
    val recommendation: WorkoutRecommendationDetail,
    val alternatives: List<AlternativeWorkout> = emptyList(),

    @JsonProperty("recommendation_type")
    val recommendationType: String = "BASIC"
)

data class WorkoutRecommendationDetail(
    @JsonProperty("workout_id")
    val workoutId: String,

    val name: String,
    val duration: Int,
    val difficulty: String,
    val exercises: List<QuickExerciseDetail>,

    @JsonProperty("estimated_calories")
    val estimatedCalories: Int,

    @JsonProperty("target_muscles")
    val targetMuscles: List<String>,

    val equipment: List<String>
)

data class QuickExerciseDetail(
    @JsonProperty("exercise_id")
    val exerciseId: String,

    val name: String,
    val category: String? = null,
    val equipment: String? = null,
    val sets: Int,
    val reps: String,
    val rest: Int,
    val order: Int,

    @JsonProperty("suggested_weight")
    val suggestedWeight: Double? = null,

    /**
     * 이 운동이 자극하는 근육군 (로컬라이즈된 표시명).
     *
     * 이 필드가 없던 동안 클라이언트는 추천 전체의 targetMuscles 를 모든 운동에 동일하게
     * 붙여서 표시했다(운동 하나가 8개 근육을 갖는 것처럼 보임).
     */
    @JsonProperty("target_muscles")
    val targetMuscles: List<String> = emptyList()
)

data class AlternativeWorkout(
    @JsonProperty("workout_id")
    val workoutId: String,

    val name: String,
    val duration: Int
)

data class WorkoutAdjustments(
    val duration: Int? = null,

    @JsonProperty("skip_exercises")
    val skipExercises: List<String> = emptyList(),

    @JsonProperty("substitute_exercises")
    val substituteExercises: Map<String, String> = emptyMap()
)
