package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.RecommendationTier

/**
 * Shared ranking rules for recommendation ordering.
 */
object RecommendationExerciseRanking {
    private val generalRecommendationTiers = setOf(
        RecommendationTier.ESSENTIAL,
        RecommendationTier.STANDARD
    )

    private val compoundKeywords = listOf(
        "press",
        "squat",
        "deadlift",
        "row",
        "pullup",
        "chinup",
        "dip",
        "lunge"
    )

    /**
     * 장비 우선순위 — 부하(load)가 높은 장비가 메인 리프트로 적합
     * 바벨 > 덤벨 > 케이블 > 머신 > 기타
     */
    private fun equipmentPriority(exercise: Exercise): Int {
        return when (exercise.equipment) {
            Equipment.BARBELL -> 0
            Equipment.DUMBBELL -> 1
            Equipment.CABLE -> 2
            Equipment.MACHINE -> 3
            Equipment.KETTLEBELL -> 4
            Equipment.BODYWEIGHT -> 5
            Equipment.RESISTANCE_BAND -> 6
            else -> 7
        }
    }

    fun isCoreCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier == RecommendationTier.ESSENTIAL || exercise.isBasicExercise
    }

    fun isGeneralCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier in generalRecommendationTiers || exercise.isBasicExercise
    }

    /**
     * 같은 패턴 내에서 "대표 운동"을 선택하는 비교자.
     *
     * 선택 기준 (우선순위 순):
     * 1. ESSENTIAL > STANDARD (추천 등급)
     * 2. 복합운동 > 고립운동
     * 3. 바벨 > 덤벨 > 케이블 > 머신 (장비 — 부하 높은 장비가 메인 리프트)
     * 4. 인기도 높은 것 우선
     * 5. 난이도 높은 것 우선 (부하가 큰 정통 운동이 변형보다 난이도 높음)
     * 6. 이름 사전순 (최종 타이브레이킹)
     */
    fun patternSelectionComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { recommendationPriority(it) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { equipmentPriority(it) },
            { -it.popularity },
            { -it.difficulty },
            { it.name.length },
            { it.name }
        )
    }

    fun displayOrderComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { categoryPriority(it.category) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { recommendationPriority(it) },
            { equipmentPriority(it) },
            { -it.popularity },
            { -it.difficulty },
            { it.name }
        )
    }

    private fun recommendationPriority(exercise: Exercise): Int {
        return when {
            exercise.recommendationTier == RecommendationTier.ESSENTIAL && exercise.isBasicExercise -> 0
            exercise.recommendationTier == RecommendationTier.ESSENTIAL -> 1
            exercise.isBasicExercise -> 2
            exercise.recommendationTier == RecommendationTier.STANDARD -> 3
            else -> 4
        }
    }

    private fun categoryPriority(category: ExerciseCategory): Int {
        return when (category) {
            ExerciseCategory.LEGS -> 1
            ExerciseCategory.BACK -> 2
            ExerciseCategory.CHEST -> 3
            ExerciseCategory.SHOULDERS -> 4
            ExerciseCategory.ARMS -> 5
            ExerciseCategory.CORE -> 6
            ExerciseCategory.CARDIO -> 7
            ExerciseCategory.FULL_BODY -> 8
        }
    }

    private fun isCompoundExercise(exercise: Exercise): Boolean {
        if (exercise.muscleGroups.size >= 2) return true

        val name = exercise.name.lowercase()
        return compoundKeywords.any { keyword -> name.contains(keyword) }
    }
}
