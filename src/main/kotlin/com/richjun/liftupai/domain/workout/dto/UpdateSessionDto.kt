package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty

// 세션 업데이트 요청 DTO
data class UpdateSessionRequest(
    @JsonProperty("exercises")
    val exercises: List<UpdateExerciseData>,

    @JsonProperty("last_updated")
    val lastUpdated: String? = null
)

data class UpdateExerciseData(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("order_index")
    val orderIndex: Int,

    @JsonProperty("sets")
    val sets: List<UpdateSetData> = emptyList()
)

data class UpdateSetData(
    @JsonProperty("set_number")
    val setNumber: Int,

    @JsonProperty("weight")
    val weight: Double,

    @JsonProperty("reps")
    val reps: Int,

    @JsonProperty("completed")
    val completed: Boolean,

    @JsonProperty("rpe")
    val rpe: Int? = null,

    @JsonProperty("rest_time")
    val restTime: Int? = null
)

// 업데이트 응답 DTO
data class UpdateSessionResponse(
    val success: Boolean,
    val message: String,

    @JsonProperty("session_id")
    val sessionId: Long? = null,

    @JsonProperty("updated_exercises")
    val updatedExercises: Int? = null,

    @JsonProperty("total_sets")
    val totalSets: Int? = null,

    @JsonProperty("completed_sets")
    val completedSets: Int? = null
)