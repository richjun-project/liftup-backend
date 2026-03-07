package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecommendationExerciseRankingTest {

    @Test
    fun `core candidate should prefer essential and basic exercises`() {
        val essentialExercise = exercise(
            name = "백 스쿼트",
            category = ExerciseCategory.LEGS,
            recommendationTier = RecommendationTier.ESSENTIAL
        )
        val basicStandardExercise = exercise(
            name = "덤벨 컬",
            category = ExerciseCategory.ARMS,
            isBasicExercise = true,
            recommendationTier = RecommendationTier.STANDARD
        )
        val standardExercise = exercise(
            name = "컨센트레이션 컬",
            category = ExerciseCategory.ARMS,
            recommendationTier = RecommendationTier.STANDARD
        )

        assertTrue(RecommendationExerciseRanking.isCoreCandidate(essentialExercise))
        assertTrue(RecommendationExerciseRanking.isCoreCandidate(basicStandardExercise))
        assertFalse(RecommendationExerciseRanking.isCoreCandidate(standardExercise))
        assertTrue(RecommendationExerciseRanking.isGeneralCandidate(standardExercise))
    }

    @Test
    fun `pattern selection should prioritize core lifts over easier variations`() {
        val standardVariation = exercise(
            name = "체스트 프레스 머신",
            category = ExerciseCategory.CHEST,
            popularity = 70,
            difficulty = 20,
            recommendationTier = RecommendationTier.STANDARD
        )
        val essentialLift = exercise(
            name = "바벨 벤치프레스",
            category = ExerciseCategory.CHEST,
            popularity = 90,
            difficulty = 45,
            isBasicExercise = true,
            recommendationTier = RecommendationTier.ESSENTIAL
        )

        val sorted = listOf(standardVariation, essentialLift)
            .sortedWith(RecommendationExerciseRanking.patternSelectionComparator())

        assertEquals("바벨 벤치프레스", sorted.first().name)
    }

    @Test
    fun `display order should keep large muscle compound exercises first`() {
        val crunch = exercise(
            name = "크런치",
            category = ExerciseCategory.CORE,
            popularity = 80,
            difficulty = 10,
            recommendationTier = RecommendationTier.ESSENTIAL
        )
        val squat = exercise(
            name = "백 스쿼트",
            category = ExerciseCategory.LEGS,
            popularity = 95,
            difficulty = 45,
            isBasicExercise = true,
            recommendationTier = RecommendationTier.ESSENTIAL
        )

        val sorted = listOf(crunch, squat)
            .sortedWith(RecommendationExerciseRanking.displayOrderComparator())

        assertEquals("백 스쿼트", sorted.first().name)
    }

    private fun exercise(
        name: String,
        category: ExerciseCategory,
        popularity: Int = 50,
        difficulty: Int = 50,
        isBasicExercise: Boolean = false,
        recommendationTier: RecommendationTier = RecommendationTier.STANDARD
    ): Exercise {
        return Exercise(
            id = 1L,
            name = name,
            category = category,
            muscleGroups = mutableSetOf(defaultMuscleGroup(category)),
            popularity = popularity,
            difficulty = difficulty,
            isBasicExercise = isBasicExercise,
            recommendationTier = recommendationTier
        )
    }

    private fun defaultMuscleGroup(category: ExerciseCategory): MuscleGroup {
        return when (category) {
            ExerciseCategory.CHEST -> MuscleGroup.CHEST
            ExerciseCategory.BACK -> MuscleGroup.BACK
            ExerciseCategory.LEGS -> MuscleGroup.QUADRICEPS
            ExerciseCategory.SHOULDERS -> MuscleGroup.SHOULDERS
            ExerciseCategory.ARMS -> MuscleGroup.BICEPS
            ExerciseCategory.CORE -> MuscleGroup.CORE
            ExerciseCategory.CARDIO -> MuscleGroup.LEGS
            ExerciseCategory.FULL_BODY -> MuscleGroup.CORE
        }
    }
}
