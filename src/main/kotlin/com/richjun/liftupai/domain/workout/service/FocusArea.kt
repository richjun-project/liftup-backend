package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup

/**
 * 온보딩에서 사용자가 고른 "집중 부위" 목표(굵은 팔·두꺼운 가슴·힙업·두꺼운 허벅지 등)를 플랜이 이해하는 부위로 옮긴 것.
 *
 * 서버의 FitnessGoal 은 6종뿐이라 세부 목표(thick_legs, glutes …)는 저장 시 MUSCLE_GAIN 등으로 뭉개진다
 * (FitnessGoalMapper). 플랜·추천이 "어느 부위를 더 할지"를 알려면 세부 목표를 따로 읽어야 한다.
 */
enum class FocusArea {
    LEGS, GLUTES, CORE, ARMS, CHEST, BACK, SHOULDERS;

    /** 이 운동이 해당 부위 볼륨으로 센다 */
    fun hits(exercise: Exercise): Boolean = when (this) {
        LEGS -> exercise.category == ExerciseCategory.LEGS
        GLUTES -> MuscleGroup.GLUTES in exercise.muscleGroups
        CORE -> exercise.category == ExerciseCategory.CORE
        ARMS -> exercise.category == ExerciseCategory.ARMS
        CHEST -> exercise.category == ExerciseCategory.CHEST
        BACK -> exercise.category == ExerciseCategory.BACK
        SHOULDERS -> exercise.category == ExerciseCategory.SHOULDERS
    }

    /** 집중 운동을 추가할 후보 카테고리 */
    fun candidateCategories(): List<ExerciseCategory> = when (this) {
        LEGS -> listOf(ExerciseCategory.LEGS)
        GLUTES -> listOf(ExerciseCategory.LEGS, ExerciseCategory.CORE)
        CORE -> listOf(ExerciseCategory.CORE)
        ARMS -> listOf(ExerciseCategory.ARMS)
        CHEST -> listOf(ExerciseCategory.CHEST)
        BACK -> listOf(ExerciseCategory.BACK)
        SHOULDERS -> listOf(ExerciseCategory.SHOULDERS)
    }

    /** 이 부위를 넣어도 어색하지 않은 날 유형인가 (밀기 날에 이두 운동을 넣지 않는다) */
    fun fitsDay(workoutType: String): Boolean {
        val type = workoutType.uppercase()
        if (type == "FULL_BODY") return true
        return when (this) {
            LEGS, GLUTES -> type in setOf("LEGS", "LOWER", "CARDIO")
            CORE -> true
            CHEST -> type in setOf("PUSH", "UPPER", "CHEST")
            BACK -> type in setOf("PULL", "UPPER", "BACK")
            SHOULDERS -> type in setOf("PUSH", "UPPER", "SHOULDERS")
            ARMS -> type in setOf("PUSH", "PULL", "UPPER", "ARMS")
        }
    }

    /** 날 유형에 맞는 세부 필터 — 밀기 날의 팔은 삼두, 당기기 날의 팔은 이두 */
    fun fitsExerciseOnDay(exercise: Exercise, workoutType: String): Boolean {
        if (this != ARMS) return true
        return when (workoutType.uppercase()) {
            "PUSH" -> MuscleGroup.TRICEPS in exercise.muscleGroups
            "PULL" -> MuscleGroup.BICEPS in exercise.muscleGroups
            else -> true
        }
    }

    companion object {
        /** 한 번에 챙기는 집중 부위 수. 5개 목표를 다 고른 사용자의 모든 날이 한 부위로 도배되지 않게 한다. */
        const val MAX_FOCUS = 3

        private val GOAL_TO_AREAS: Map<String, List<FocusArea>> = mapOf(
            "thick_legs" to listOf(LEGS),
            "slim_legs" to listOf(LEGS, GLUTES),
            "glutes" to listOf(GLUTES),
            "six_pack" to listOf(CORE),
            "core_strength" to listOf(CORE),
            "big_arms" to listOf(ARMS),
            "toned_arms" to listOf(ARMS),
            "thick_chest" to listOf(CHEST),
            "wide_back" to listOf(BACK),
            "wide_shoulders" to listOf(SHOULDERS),
        )

        /** 목표 값(bulk_up, thick_legs …) 가 집중 부위 목표인지 */
        fun isFocusGoal(goal: String): Boolean = normalize(goal) in GOAL_TO_AREAS

        /**
         * 목표 목록(고른 순서) + 앱이 보낸 focus_areas(legs, glutes …) → 집중 부위. 중복 제거, 최대 [MAX_FOCUS]개.
         * 목표 순서를 존중한다 — 사용자가 먼저 고른 것이 더 중요하다.
         */
        fun resolve(goals: Collection<String>?, focusAreas: Collection<String>? = null, limit: Int = MAX_FOCUS): List<FocusArea> {
            val result = LinkedHashSet<FocusArea>()
            goals.orEmpty().forEach { g -> GOAL_TO_AREAS[normalize(g)]?.let { result.addAll(it) } }
            focusAreas.orEmpty().forEach { a -> parse(a)?.let { result.add(it) } }
            return result.take(limit)
        }

        fun parse(value: String?): FocusArea? {
            val key = value?.trim()?.uppercase()?.replace('-', '_')?.replace(' ', '_') ?: return null
            return values().firstOrNull { it.name == key }
        }

        private fun normalize(goal: String) = goal.trim().lowercase().replace('-', '_').replace(' ', '_')
    }
}
