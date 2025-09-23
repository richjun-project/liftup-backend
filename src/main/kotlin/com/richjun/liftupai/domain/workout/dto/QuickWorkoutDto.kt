package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty

// Quick workout recommendation DTOs for V3 requirements

// GET /api/workouts/recommendations/quick Response
data class QuickWorkoutRecommendationResponse(
    val recommendation: WorkoutRecommendationDetail,
    val alternatives: List<AlternativeWorkout> = emptyList()
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
    val sets: Int,
    val reps: String,
    val rest: Int,
    val order: Int,

    @JsonProperty("suggested_weight")
    val suggestedWeight: Double? = null
)

data class AlternativeWorkout(
    @JsonProperty("workout_id")
    val workoutId: String,

    val name: String,
    val duration: Int
)

// POST /api/workouts/start-recommended Request/Response
data class StartRecommendedWorkoutRequest(
    @JsonProperty("recommendation_id")
    val recommendationId: String,

    val adjustments: WorkoutAdjustments = WorkoutAdjustments()
)

data class WorkoutAdjustments(
    val duration: Int? = null,

    @JsonProperty("skip_exercises")
    val skipExercises: List<String> = emptyList(),

    @JsonProperty("substitute_exercises")
    val substituteExercises: Map<String, String> = emptyMap()
)

data class StartRecommendedWorkoutResponse(
    @JsonProperty("session_id")
    val sessionId: String,

    @JsonProperty("workout_name")
    val workoutName: String,

    @JsonProperty("start_time")
    val startTime: String,

    val exercises: List<RecommendedWorkoutExercise>,

    @JsonProperty("estimated_duration")
    val estimatedDuration: Int,

    val started: Boolean
)

data class RecommendedWorkoutExercise(
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
    val restTimer: Int
)