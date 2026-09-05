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

    @Test
    fun `balanceByCategory without target should spread one exercise per category`() {
        val input = listOf(
            exercise(name = "스쿼트", category = ExerciseCategory.LEGS),
            exercise(name = "런지", category = ExerciseCategory.LEGS),
            exercise(name = "데드리프트", category = ExerciseCategory.BACK),
            exercise(name = "벤치프레스", category = ExerciseCategory.CHEST)
        )

        val result = RecommendationExerciseRanking.balanceByCategory(input, targetCategory = null)

        assertEquals(
            listOf(ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST, ExerciseCategory.LEGS),
            result.map { it.category }
        )
    }

    @Test
    fun `balanceByCategory should preserve incoming order within a category`() {
        // 카테고리 안에서 재정렬하면 상위 단계의 개인화(주간 볼륨)/벡터 유사도 순서가 폐기된다.
        // 인기도가 낮은 운동이 먼저 들어왔다면 그 순서가 유지되어야 한다.
        val first = exercise(name = "먼저 온 운동", category = ExerciseCategory.LEGS, popularity = 10)
        val second = exercise(name = "나중에 온 운동", category = ExerciseCategory.LEGS, popularity = 99)

        val result = RecommendationExerciseRanking.balanceByCategory(listOf(first, second))

        assertEquals(listOf("먼저 온 운동", "나중에 온 운동"), result.map { it.name })
    }

    @Test
    fun `balanceByCategory with target should fill most slots from the target category`() {
        // "가슴 5개" 요청이 가슴 1개 + 무관한 부위 4개가 되면 안 된다.
        val input = listOf(
            exercise(name = "벤치프레스", category = ExerciseCategory.CHEST),
            exercise(name = "인클라인 프레스", category = ExerciseCategory.CHEST),
            exercise(name = "덤벨 플라이", category = ExerciseCategory.CHEST),
            exercise(name = "숄더프레스", category = ExerciseCategory.SHOULDERS),
            exercise(name = "트라이셉 익스텐션", category = ExerciseCategory.ARMS),
            exercise(name = "스쿼트", category = ExerciseCategory.LEGS)
        )

        val top5 = RecommendationExerciseRanking
            .balanceByCategory(input, targetCategory = ExerciseCategory.CHEST)
            .take(5)

        assertEquals(
            3,
            top5.count { it.category == ExerciseCategory.CHEST },
            "가슴 운동 3개가 상위에 와야 함: ${top5.map { it.name }}"
        )
        assertEquals(ExerciseCategory.CHEST, top5.first().category)
        // 보조 부위는 해부학적으로 인접한 어깨/삼두가 먼저 — 하체가 상위 5개에 끼면 안 된다
        assertFalse(
            top5.any { it.category == ExerciseCategory.LEGS },
            "가슴 타겟인데 하체가 상위에 포함됨: ${top5.map { it.name }}"
        )
    }

    @Test
    fun `balanceByCategory should not drop any exercise`() {
        val input = ExerciseCategory.entries.flatMapIndexed { index, category ->
            List(index % 3 + 1) { exercise(name = "$category-$it", category = category) }
        }

        val result = RecommendationExerciseRanking.balanceByCategory(input, ExerciseCategory.BACK)

        assertEquals(input.size, result.size)
        assertEquals(input.map { it.name }.toSet(), result.map { it.name }.toSet())
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
            slug = name.lowercase().replace(" ", "-"),
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
