package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty

// 프로그램 진행 상황 DTO
data class ProgramStatusResponse(
    @JsonProperty("current_day")
    val currentDay: Int,  // 현재 회차 (1, 2, 3...)

    @JsonProperty("total_days")
    val totalDays: Int,   // 전체 회차 (PPL은 3, Upper/Lower는 2)

    @JsonProperty("current_cycle")
    val currentCycle: Int,  // 현재 사이클 (몇 번째 반복)

    @JsonProperty("next_workout_type")
    val nextWorkoutType: String,  // 다음 운동 유형 (PUSH, PULL, LEGS 등)

    @JsonProperty("next_workout_description")
    val nextWorkoutDescription: String,  // "주 3회 프로그램 중 2회차: 당기기 운동 (등/이두)"

    @JsonProperty("program_type")
    val programType: String,  // PPL, UPPER_LOWER, BRO_SPLIT 등

    @JsonProperty("last_workout_date")
    val lastWorkoutDate: String?,  // 마지막 운동 날짜

    @JsonProperty("is_new_cycle")
    val isNewCycle: Boolean,  // 새로운 사이클 시작 여부

    @JsonProperty("workout_history")
    val workoutHistory: List<WorkoutHistoryItem>,  // 최근 운동 기록

    @JsonProperty("recommendation_reason")
    val recommendationReason: String? = null,  // AUTO 프로그램 추천 이유

    @JsonProperty("recommendation_confidence")
    val recommendationConfidence: Double? = null  // 추천 신뢰도 (0.0 ~ 1.0)
)

data class WorkoutHistoryItem(
    @JsonProperty("day_number")
    val dayNumber: Int,

    @JsonProperty("workout_type")
    val workoutType: String,

    val date: String,

    val status: String,  // COMPLETED, IN_PROGRESS, CANCELLED

    @JsonProperty("cycle_number")
    val cycleNumber: Int
)