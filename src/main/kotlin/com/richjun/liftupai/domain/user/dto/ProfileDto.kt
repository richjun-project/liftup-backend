package com.richjun.liftupai.domain.user.dto

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDateTime

data class ProfileRequest(
    val nickname: String,
    val experienceLevel: String,
    val goals: List<String>,
    val height: Double?,
    val weight: Double?,
    val age: Int?,
    val gender: String?,
    val ptStyle: String,
    val notificationEnabled: Boolean = true,
    val weeklyWorkoutDays: Int = 3,
    val workoutSplit: String = "full_body",
    val availableEquipment: List<String> = emptyList(),
    val preferredWorkoutTime: String = "evening",
    val workoutDuration: Int = 60,
    val injuries: List<String> = emptyList()
)

data class ProfileUpdateRequest(
    val nickname: String? = null,
    val experienceLevel: String? = null,
    val goals: List<String>? = null,
    val height: Double? = null,
    val weight: Double? = null,
    val age: Int? = null,
    val gender: String? = null,
    val ptStyle: String? = null,
    val notificationEnabled: Boolean? = null,
    val weeklyWorkoutDays: Int? = null,
    val workoutSplit: String? = null,
    val availableEquipment: List<String>? = null,
    val preferredWorkoutTime: String? = null,
    val workoutDuration: Int? = null,
    val injuries: List<String>? = null
)

data class ProfileResponse(
    val id: String,
    val nickname: String?,
    val experienceLevel: String,
    val goals: List<String>,
    val height: Double?,
    val weight: Double?,
    val age: Int?,
    val gender: String?,
    val ptStyle: String,
    val notificationEnabled: Boolean,
    val weeklyWorkoutDays: Int,
    val workoutSplit: String,
    val availableEquipment: List<String>,
    val preferredWorkoutTime: String,
    val workoutDuration: Int,
    val injuries: List<String>,
    val currentProgram: String?,
    val currentWeek: Int,
    val lastWorkoutDate: LocalDateTime?,
    val muscleRecovery: Map<String, String>?,
    val strengthTestCompleted: Boolean,
    val estimatedMaxes: Map<String, Double>?,
    val workingWeights: Map<String, Double>?,
    val strengthLevel: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class WorkoutPlanRequest(
    val weeklyWorkoutDays: Int,
    val workoutSplit: String,
    val preferredWorkoutTime: String = "evening",
    val workoutDuration: Int = 60,
    val availableEquipment: List<String> = emptyList()
)

data class WorkoutPlanResponse(
    val weeklyWorkoutDays: Int,
    val workoutSplit: String,
    val preferredWorkoutTime: String,
    val workoutDuration: Int,
    val availableEquipment: List<String>,
    val recommendedProgram: String
)

data class GenerateProgramRequest(
    val weeklyWorkoutDays: Int,
    val workoutSplit: String,
    val experienceLevel: String,
    val goals: List<String>,
    val availableEquipment: List<String>,
    val duration: Int
)

data class ProgramSchedule(
    val monday: WorkoutDay?,
    val tuesday: WorkoutDay?,
    val wednesday: WorkoutDay?,
    val thursday: WorkoutDay?,
    val friday: WorkoutDay?,
    val saturday: WorkoutDay?,
    val sunday: WorkoutDay?
)

data class WorkoutDay(
    val name: String,
    val exercises: List<ExercisePlan>
)

data class ExercisePlan(
    val name: String,
    val sets: Int,
    val reps: String,
    val rest: Int
)

data class GeneratedProgramResponse(
    val programName: String,
    val weeks: Int,
    val schedule: ProgramSchedule
)

data class TodayWorkoutRequest(
    @JsonProperty("weekly_workout_days")
    val weeklyWorkoutDays: Int = 3,
    @JsonProperty("workout_split")
    val workoutSplit: String = "full_body",
    @JsonProperty("experience_level")
    val experienceLevel: String = "beginner",
    val goals: List<String> = emptyList(),
    @JsonProperty("last_workout_date")
    val lastWorkoutDate: LocalDateTime? = null,
    @JsonProperty("muscle_recovery")
    val muscleRecovery: Map<String, String>? = null
)

data class TodayWorkoutResponse(
    val workoutName: String,
    val targetMuscles: List<String>,
    val estimatedDuration: Int,
    val exercises: List<ExerciseDetailV4>,
    val reason: String
)

data class ExerciseDetailV4(
    val id: String,
    val name: String,
    val targetMuscle: String,
    val sets: List<SetDetail>,
    val restTime: Int,
    val tips: String
)

data class SetDetail(
    val setNumber: Int,
    val reps: Int,
    val weight: Double,
    val type: String // warm_up, working
)

data class WeeklyStatsResponse(
    val targetDays: Int,
    val completedDays: Int,
    val totalVolume: Double,
    val totalSets: Int,
    val totalReps: Int,
    val workoutDates: List<String>,
    val nextWorkoutDay: String?,
    val weeklyProgress: Int // percentage
)