package com.richjun.liftupai.domain.workout.service

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

    fun isCoreCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier == RecommendationTier.ESSENTIAL || exercise.isBasicExercise
    }

    fun isGeneralCandidate(exercise: Exercise): Boolean {
        return exercise.recommendationTier in generalRecommendationTiers || exercise.isBasicExercise
    }

    fun patternSelectionComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { recommendationPriority(it) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { -it.popularity },
            { it.difficulty },
            { it.name }
        )
    }

    fun displayOrderComparator(): Comparator<Exercise> {
        return compareBy<Exercise>(
            { categoryPriority(it.category) },
            { if (isCompoundExercise(it)) 0 else 1 },
            { recommendationPriority(it) },
            { -it.popularity },
            { it.difficulty },
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
