package com.richjun.liftupai.domain.workout.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.NotBlank

// V2 Request/Response DTOs

// 운동 시작 V2
data class StartWorkoutRequestV2(
    @JsonProperty("exercises")
    val plannedExercises: List<PlannedExercise> = emptyList(),

    @JsonProperty("workout_type")
    val workoutType: String = "empty", // empty, quick, ai, program

    @JsonProperty("program_id")
    val programId: String? = null,

    // Quick 추천 운동용
    @JsonProperty("recommendation_id")
    val recommendationId: String? = null,

    @JsonProperty("adjustments")
    val adjustments: com.richjun.liftupai.domain.workout.dto.WorkoutAdjustments? = null,

    // AI 추천 운동용
    @JsonProperty("ai_workout")
    val aiWorkout: com.richjun.liftupai.domain.workout.dto.WorkoutRecommendationDetail? = null
)

data class StartWorkoutResponseV2(
    @JsonProperty("session_id")
    val sessionId: Long,

    @JsonProperty("start_time")
    val startTime: String,

    val exercises: List<ExerciseDto> = emptyList(),

    @JsonProperty("rest_timer_settings")
    val restTimerSettings: RestTimerSettings,

    @JsonProperty("exercise_sets")
    val exerciseSets: List<ExerciseWithSets>? = null  // Optional, for continue workout
)

data class RestTimerSettings(
    @JsonProperty("default_rest_seconds")
    val defaultRestSeconds: Int = 90,

    @JsonProperty("auto_start_timer")
    val autoStartTimer: Boolean = true
)

// 운동 완료 V2
data class CompleteWorkoutRequestV2(
    val exercises: List<CompletedExerciseV2> = emptyList(),
    val duration: Int = 0,
    val notes: String? = null
)

data class CompletedExerciseV2(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    val sets: List<ExerciseSetV2> = emptyList()
)

data class ExerciseSetV2(
    val weight: Double,
    val reps: Int,
    val completed: Boolean = true,

    @JsonProperty("completed_at")
    val completedAt: String? = null,

    @JsonProperty("rest_taken")
    val restTaken: Int? = null
)

data class CompleteWorkoutResponseV2(
    val success: Boolean,
    val summary: WorkoutSummaryV2,
    val achievements: AchievementsInfo,
    val stats: WorkoutStats
)

data class WorkoutSummaryV2(
    val duration: Int,

    @JsonProperty("total_volume")
    val totalVolume: Double,

    @JsonProperty("total_sets")
    val totalSets: Int,

    @JsonProperty("exercise_count")
    val exerciseCount: Int,

    @JsonProperty("calories_burned")
    val caloriesBurned: Int
)

data class AchievementsInfo(
    @JsonProperty("new_personal_records")
    val newPersonalRecords: List<PersonalRecordInfo> = emptyList(),

    val milestones: List<String> = emptyList()
)

data class PersonalRecordInfo(
    @JsonProperty("exercise_name")
    val exerciseName: String,

    val weight: Double,
    val reps: Int,

    @JsonProperty("previous_best")
    val previousBest: Double
)

data class WorkoutStats(
    @JsonProperty("total_workout_days")
    val totalWorkoutDays: Int,

    @JsonProperty("current_week_count")
    val currentWeekCount: Int,

    @JsonProperty("weekly_goal")
    val weeklyGoal: Int,

    @JsonProperty("current_streak")
    val currentStreak: Int,

    @JsonProperty("longest_streak")
    val longestStreak: Int
)

// 실시간 세트 업데이트
data class UpdateSetRequest(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("set_number")
    val setNumber: Int,

    val weight: Double,
    val reps: Int,
    val completed: Boolean,

    @JsonProperty("completed_at")
    val completedAt: String
)

data class UpdateSetResponse(
    val success: Boolean,

    @JsonProperty("set_id")
    val setId: Long,

    @JsonProperty("is_personal_record")
    val isPersonalRecord: Boolean,

    @JsonProperty("previous_best")
    val previousBest: PreviousBest? = null
)

data class PreviousBest(
    val weight: Double,
    val reps: Int,
    val date: String
)

// 운동 상세 정보 V2
data class ExerciseDetailV2(
    val id: Long,
    val name: String,
    val category: String,

    @JsonProperty("muscle_groups")
    val muscleGroups: List<String>,

    val equipment: String?,

    @JsonProperty("image_url")
    val imageUrl: String? = null,

    @JsonProperty("thumbnail_url")
    val thumbnailUrl: String? = null,

    @JsonProperty("video_url")
    val videoUrl: String? = null,

    val difficulty: String = "intermediate",
    val description: String? = null,
    val instructions: List<String> = emptyList(),
    val tips: List<String> = emptyList(),

    @JsonProperty("common_mistakes")
    val commonMistakes: List<String> = emptyList(),

    val breathing: String? = null
)

data class ExerciseDetailResponseV2(
    val exercise: ExerciseDetailV2,

    @JsonProperty("user_stats")
    val userStats: UserExerciseStats
)

data class UserExerciseStats(
    @JsonProperty("personal_record")
    val personalRecord: PersonalRecord? = null,

    @JsonProperty("last_performed")
    val lastPerformed: String? = null,

    @JsonProperty("total_sets")
    val totalSets: Int = 0,

    @JsonProperty("average_weight")
    val averageWeight: Double = 0.0,

    @JsonProperty("estimated_one_rep_max")
    val estimatedOneRepMax: Double = 0.0
)

// 운동 완료 통계
data class WorkoutCompletionStats(
    val session: SessionStats,
    val history: HistoryStats,
    val streaks: StreakStats,
    val achievements: List<Achievement>,
    val comparison: ComparisonStats
)

data class SessionStats(
    val duration: Int,

    @JsonProperty("total_volume")
    val totalVolume: Double,

    @JsonProperty("total_sets")
    val totalSets: Int,

    @JsonProperty("exercise_count")
    val exerciseCount: Int
)

data class HistoryStats(
    @JsonProperty("total_workout_days")
    val totalWorkoutDays: Int,

    @JsonProperty("total_workouts")
    val totalWorkouts: Int,

    @JsonProperty("member_since")
    val memberSince: String,

    @JsonProperty("average_workouts_per_week")
    val averageWorkoutsPerWeek: Double
)

data class StreakStats(
    val current: Int,
    val longest: Int,

    @JsonProperty("weekly_count")
    val weeklyCount: Int,

    @JsonProperty("weekly_goal")
    val weeklyGoal: Int,

    @JsonProperty("monthly_count")
    val monthlyCount: Int,

    @JsonProperty("monthly_goal")
    val monthlyGoal: Int
)

data class Achievement(
    val id: String,
    val name: String,
    val description: String,

    @JsonProperty("unlocked_at")
    val unlockedAt: String,

    val icon: String
)

data class ComparisonStats(
    @JsonProperty("volume_change")
    val volumeChange: String,

    @JsonProperty("duration_change")
    val durationChange: String,

    @JsonProperty("compared_to")
    val comparedTo: String
)

// 캘린더
data class WorkoutCalendarResponse(
    val calendar: List<CalendarDay>,
    val summary: CalendarSummary
)

data class CalendarDay(
    val date: String,

    @JsonProperty("has_workout")
    val hasWorkout: Boolean,

    @JsonProperty("workout_count")
    val workoutCount: Int,

    @JsonProperty("total_volume")
    val totalVolume: Double? = null,

    @JsonProperty("primary_muscles")
    val primaryMuscles: List<String> = emptyList()
)

data class CalendarSummary(
    @JsonProperty("total_days")
    val totalDays: Int,

    @JsonProperty("rest_days")
    val restDays: Int,

    @JsonProperty("average_volume")
    val averageVolume: Double,

    @JsonProperty("most_frequent_day")
    val mostFrequentDay: String
)

// 운동 조정
data class AdjustNextSetRequest(
    @JsonProperty("session_id")
    val sessionId: Long,

    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("previous_set")
    val previousSet: PreviousSetInfo,

    val fatigue: String // low, medium, high
)

data class PreviousSetInfo(
    val weight: Double,
    val reps: Int,
    val rpe: Int
)

data class AdjustNextSetResponse(
    val recommendation: SetRecommendation,
    val alternatives: List<AlternativeSet>
)

data class SetRecommendation(
    val weight: Double,
    val reps: Int,

    @JsonProperty("rest_seconds")
    val restSeconds: Int,

    val reason: String
)

data class AlternativeSet(
    val type: String, // drop_set, rest_pause, etc
    val weight: Double,
    val reps: Int,
    val description: String
)

// 휴식 타이머
data class RestTimerResponse(
    @JsonProperty("recommended_rest")
    val recommendedRest: Int,

    @JsonProperty("min_rest")
    val minRest: Int,

    @JsonProperty("max_rest")
    val maxRest: Int,

    val factors: RestFactors
)

data class RestFactors(
    @JsonProperty("exercise_type")
    val exerciseType: String,

    val intensity: String,

    @JsonProperty("set_number")
    val setNumber: String
)

// 운동과 세트 정보를 함께 담는 DTO
data class ExerciseWithSets(
    @JsonProperty("exercise_id")
    val exerciseId: Long,

    @JsonProperty("exercise_name")
    val exerciseName: String,

    @JsonProperty("order_index")
    val orderIndex: Int,

    val sets: List<SetInfo> = emptyList()
)

data class SetInfo(
    @JsonProperty("set_id")
    val setId: Long? = null,

    @JsonProperty("set_number")
    val setNumber: Int,

    val weight: Double? = null,
    val reps: Int? = null,
    val completed: Boolean = false,

    @JsonProperty("completed_at")
    val completedAt: String? = null
)