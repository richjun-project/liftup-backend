package com.richjun.liftupai.domain.workout.dto.common

import com.fasterxml.jackson.annotation.JsonProperty

data class ExerciseSetDto(
    val reps: Int,
    val weight: Double? = null,
    @JsonProperty("rest_time") val restTime: Int? = null,
    val completed: Boolean = false,
    val notes: String? = null
)

data class PlannedExerciseDto(
    @JsonProperty("exercise_id") val exerciseId: Long,
    val sets: Int = 3,
    @JsonProperty("target_reps") val targetReps: Int = 10,
    val weight: Double? = null,
    @JsonProperty("order_index") val orderIndex: Int? = null
)

data class CompletedExerciseDto(
    @JsonProperty("exercise_id") val exerciseId: Long,
    val sets: List<ExerciseSetDto> = emptyList()
)

data class WorkoutExerciseDto(
    val id: Long,
    @JsonProperty("exercise_id") val exerciseId: Long,
    @JsonProperty("exercise_name") val exerciseName: String,
    val category: String,
    val equipment: String?,
    val sets: List<ExerciseSetDto>,
    @JsonProperty("order_in_session") val orderInSession: Int = 0,
    @JsonProperty("target_sets") val targetSets: Int? = null,
    @JsonProperty("target_reps") val targetReps: Int? = null
)