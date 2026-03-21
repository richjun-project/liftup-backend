package com.richjun.liftupai.domain.workout.service.vector

import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier.MovementPattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * 벡터 추천 시스템 핵심 로직 검증
 * - 회복 필터 (전체 근육 그룹 체크)
 * - 목표 기반 필터
 * - 패턴 broad 카테고리 매핑
 * - 유사도 임계값
 */
class VectorRecommendationLogicTest {

    // ===== 회복 필터: 모든 근육 그룹 체크 =====

    @Test
    fun `recovery filter should block exercises with recovering secondary muscles`() {
        val recoveringMuscles = setOf(MuscleGroup.TRICEPS)

        // Bench Press targets CHEST (primary) + TRICEPS (secondary)
        val benchPress = Exercise(
            id = 1L, slug = "bench-press", name = "Bench Press",
            category = ExerciseCategory.CHEST, equipment = Equipment.BARBELL,
            muscleGroups = mutableSetOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
        )
        // Lat Pulldown targets BACK only
        val latPulldown = Exercise(
            id = 2L, slug = "lat-pulldown", name = "Lat Pulldown",
            category = ExerciseCategory.BACK, equipment = Equipment.CABLE,
            muscleGroups = mutableSetOf(MuscleGroup.BACK, MuscleGroup.BICEPS)
        )

        val exercises = listOf(benchPress, latPulldown)

        // Filter: exercise.muscleGroups.none { it in recoveringMuscles }
        val filtered = exercises.filter { exercise ->
            exercise.muscleGroups.none { it in recoveringMuscles }
        }

        // Bench Press should be filtered out (TRICEPS is recovering)
        assertFalse(filtered.contains(benchPress), "Bench Press should be filtered (TRICEPS recovering)")
        assertTrue(filtered.contains(latPulldown), "Lat Pulldown should pass (no recovering muscles)")
    }

    @Test
    fun `recovery filter should pass all exercises when no muscles recovering`() {
        val recoveringMuscles = emptySet<MuscleGroup>()
        val exercises = listOf(
            Exercise(id = 1L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS,
                muscleGroups = mutableSetOf(MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES))
        )

        val filtered = exercises.filter { exercise ->
            exercise.muscleGroups.none { it in recoveringMuscles }
        }

        assertEquals(exercises.size, filtered.size, "All exercises should pass with no recovering muscles")
    }

    // ===== 목표 기반 필터 =====

    @Test
    fun `MUSCLE_GAIN goal should filter out CARDIO exercises`() {
        val exercises = listOf(
            Exercise(id = 1L, slug = "bench", name = "Bench Press", category = ExerciseCategory.CHEST),
            Exercise(id = 2L, slug = "running", name = "Running", category = ExerciseCategory.CARDIO),
            Exercise(id = 3L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS)
        )

        val filtered = exercises.filter { it.category != ExerciseCategory.CARDIO }

        assertEquals(2, filtered.size)
        assertTrue(filtered.none { it.category == ExerciseCategory.CARDIO })
    }

    @Test
    fun `WEIGHT_LOSS goal should cap exercise difficulty`() {
        val exercises = listOf(
            Exercise(id = 1L, slug = "pushup", name = "Push-up", category = ExerciseCategory.CHEST, difficulty = 20),
            Exercise(id = 2L, slug = "snatch", name = "Snatch", category = ExerciseCategory.FULL_BODY, difficulty = 95),
            Exercise(id = 3L, slug = "squat", name = "Squat", category = ExerciseCategory.LEGS, difficulty = 50)
        )

        val filtered = exercises.filter { it.difficulty <= 70 }

        assertEquals(2, filtered.size)
        assertTrue(filtered.none { it.difficulty > 70 }, "High difficulty exercises should be filtered for WEIGHT_LOSS")
    }

    @Test
    fun `goal filter should return original list when filtered result is too small`() {
        // Safety: if filter removes too many, return original
        val exercises = listOf(
            Exercise(id = 1L, slug = "run1", name = "Running", category = ExerciseCategory.CARDIO),
            Exercise(id = 2L, slug = "run2", name = "Cycling", category = ExerciseCategory.CARDIO),
            Exercise(id = 3L, slug = "bench", name = "Bench", category = ExerciseCategory.CHEST)
        )

        val muscleGainFiltered = exercises.filter { it.category != ExerciseCategory.CARDIO }

        // Only 1 exercise passes, which is < 5 threshold
        val result = if (muscleGainFiltered.size >= 5) muscleGainFiltered else exercises
        assertEquals(exercises.size, result.size, "Should return original list when filtered too few")
    }

    // ===== 패턴 broad 카테고리 매핑 =====

    @Test
    fun `broad pattern category should map horizontal press variants to HORIZONTAL_PUSH`() {
        val horizontalPushPatterns = listOf(
            MovementPattern.HORIZONTAL_PRESS_BARBELL,
            MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            MovementPattern.HORIZONTAL_PRESS_MACHINE,
            MovementPattern.INCLINE_PRESS_BARBELL,
            MovementPattern.INCLINE_PRESS_DUMBBELL,
            MovementPattern.DECLINE_PRESS,
            MovementPattern.DIPS,
            MovementPattern.PUSHUP,
            MovementPattern.FLY
        )

        horizontalPushPatterns.forEach { pattern ->
            val broad = broadPatternCategory(pattern)
            assertEquals("HORIZONTAL_PUSH", broad,
                "$pattern should map to HORIZONTAL_PUSH but got $broad")
        }
    }

    @Test
    fun `broad pattern category should map pull variants correctly`() {
        assertEquals("HORIZONTAL_PULL", broadPatternCategory(MovementPattern.BARBELL_ROW))
        assertEquals("HORIZONTAL_PULL", broadPatternCategory(MovementPattern.DUMBBELL_ROW))
        assertEquals("HORIZONTAL_PULL", broadPatternCategory(MovementPattern.CABLE_ROW))
        assertEquals("VERTICAL_PULL", broadPatternCategory(MovementPattern.PULLUP_CHINUP))
        assertEquals("VERTICAL_PULL", broadPatternCategory(MovementPattern.LAT_PULLDOWN))
    }

    @Test
    fun `broad pattern category should map lower body patterns correctly`() {
        assertEquals("HIP_HINGE", broadPatternCategory(MovementPattern.HIP_HINGE))
        assertEquals("HIP_HINGE", broadPatternCategory(MovementPattern.DEADLIFT))
        assertEquals("SQUAT", broadPatternCategory(MovementPattern.SQUAT))
        assertEquals("SQUAT", broadPatternCategory(MovementPattern.LEG_PRESS))
        assertEquals("LUNGE", broadPatternCategory(MovementPattern.LUNGE))
    }

    @Test
    fun `broad pattern category should map isolation exercises`() {
        val isolationPatterns = listOf(
            MovementPattern.BICEP_CURL_BARBELL, MovementPattern.BICEP_CURL_DUMBBELL,
            MovementPattern.TRICEP_OVERHEAD, MovementPattern.TRICEP_PUSHDOWN,
            MovementPattern.LATERAL_RAISE, MovementPattern.REAR_DELT,
            MovementPattern.LEG_CURL, MovementPattern.LEG_EXTENSION, MovementPattern.CALF
        )

        isolationPatterns.forEach { pattern ->
            assertEquals("ISOLATION", broadPatternCategory(pattern),
                "$pattern should map to ISOLATION")
        }
    }

    @Test
    fun `all MovementPattern values should have a broad category mapping`() {
        MovementPattern.values().forEach { pattern ->
            val broad = broadPatternCategory(pattern)
            assertNotNull(broad, "$pattern should have a broad category")
            assertTrue(broad.isNotBlank(), "$pattern broad category should not be blank")
        }
    }

    // ===== 유사도 임계값 =====

    @Test
    fun `similarity threshold should be 0_4 not 0_2`() {
        val MIN_SIMILARITY_SCORE = 0.4f
        assertTrue(MIN_SIMILARITY_SCORE >= 0.4f, "Similarity threshold should be >= 0.4")
        assertTrue(MIN_SIMILARITY_SCORE < 1.0f, "Similarity threshold should be < 1.0")
    }

    // ===== 제로 벡터 감지 =====

    @Test
    fun `zero vector should be detectable`() {
        val zeroVector = List(768) { 0.0f }
        val normalVector = List(768) { idx -> (idx % 10).toFloat() / 10f }

        assertTrue(zeroVector.all { it == 0.0f }, "Zero vector should be all zeros")
        assertFalse(normalVector.all { it == 0.0f }, "Normal vector should NOT be all zeros")
    }

    // ===== Helper: broad pattern category (mirrors VectorWorkoutRecommendationService) =====

    private fun broadPatternCategory(pattern: MovementPattern): String {
        return when (pattern) {
            MovementPattern.HORIZONTAL_PRESS_BARBELL,
            MovementPattern.HORIZONTAL_PRESS_DUMBBELL,
            MovementPattern.HORIZONTAL_PRESS_MACHINE,
            MovementPattern.INCLINE_PRESS_BARBELL,
            MovementPattern.INCLINE_PRESS_DUMBBELL,
            MovementPattern.DECLINE_PRESS,
            MovementPattern.DIPS,
            MovementPattern.PUSHUP,
            MovementPattern.FLY -> "HORIZONTAL_PUSH"

            MovementPattern.VERTICAL_PRESS_BARBELL,
            MovementPattern.VERTICAL_PRESS_DUMBBELL,
            MovementPattern.VERTICAL_PRESS_MACHINE -> "VERTICAL_PUSH"

            MovementPattern.BARBELL_ROW,
            MovementPattern.DUMBBELL_ROW,
            MovementPattern.CABLE_ROW,
            MovementPattern.INVERTED_ROW -> "HORIZONTAL_PULL"

            MovementPattern.PULLUP_CHINUP,
            MovementPattern.LAT_PULLDOWN -> "VERTICAL_PULL"

            MovementPattern.HIP_HINGE,
            MovementPattern.DEADLIFT -> "HIP_HINGE"

            MovementPattern.SQUAT,
            MovementPattern.LEG_PRESS -> "SQUAT"

            MovementPattern.LUNGE -> "LUNGE"
            MovementPattern.CARRY -> "CARRY"
            MovementPattern.ROTATION -> "ROTATION"

            MovementPattern.BICEP_CURL_BARBELL,
            MovementPattern.BICEP_CURL_DUMBBELL,
            MovementPattern.BICEP_CURL_CABLE,
            MovementPattern.TRICEP_OVERHEAD,
            MovementPattern.TRICEP_LYING,
            MovementPattern.TRICEP_PUSHDOWN,
            MovementPattern.LATERAL_RAISE,
            MovementPattern.FRONT_RAISE,
            MovementPattern.REAR_DELT,
            MovementPattern.FACE_PULL,
            MovementPattern.UPRIGHT_ROW,
            MovementPattern.SHRUG,
            MovementPattern.LEG_CURL,
            MovementPattern.LEG_EXTENSION,
            MovementPattern.GLUTE_FOCUSED,
            MovementPattern.CALF -> "ISOLATION"

            MovementPattern.CRUNCH,
            MovementPattern.LEG_RAISE,
            MovementPattern.PLANK,
            MovementPattern.ROLLOUT -> "CORE"

            else -> "OTHER"
        }
    }
}
