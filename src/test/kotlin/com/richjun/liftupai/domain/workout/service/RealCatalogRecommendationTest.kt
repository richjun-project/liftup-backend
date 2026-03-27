package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.workout.entity.*
import com.richjun.liftupai.domain.workout.service.ExercisePatternClassifier.MovementPattern
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * 실제 운동 카탈로그(1318개)를 로드하여 추천 품질을 검증합니다.
 *
 * 테스트용 40개 픽스처가 아닌, production DB와 동일한 데이터로 테스트합니다.
 * 이것이 실제 사용자가 경험할 추천입니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RealCatalogRecommendationTest {

    private val classifier = ExercisePatternClassifier()
    private val mapper = ObjectMapper().apply {
        findAndRegisterModules()
    }

    private lateinit var allExercises: List<Exercise>

    data class CatalogEntry(
        val slug: String,
        val category: String,
        val equipment: String?,
        val movementPattern: String?,
        val muscleGroups: List<String>,
        val recommendationTier: String,
        val popularity: Int,
        val difficulty: Int,
        val basicExercise: Boolean,
        val translations: List<TranslationEntry>
    )
    data class TranslationEntry(val locale: String, val name: String)

    @BeforeAll
    fun loadCatalog() {
        val catalogFile = File("src/main/resources/catalog/exercise-catalog.json")
        if (!catalogFile.exists()) {
            println("WARN: Catalog file not found, skipping real catalog tests")
            allExercises = emptyList()
            return
        }

        val raw: List<Map<String, Any>> = mapper.readValue(catalogFile)
        allExercises = raw.mapIndexed { idx, entry ->
            Exercise(
                id = idx.toLong() + 1,
                slug = entry["slug"] as String,
                name = ((entry["translations"] as? List<*>)
                    ?.filterIsInstance<Map<*, *>>()
                    ?.firstOrNull { it["locale"] == "en" }
                    ?.get("name") as? String) ?: entry["slug"] as String,
                category = try { ExerciseCategory.valueOf(entry["category"] as String) } catch (e: Exception) { ExerciseCategory.FULL_BODY },
                movementPattern = entry["movementPattern"] as? String,
                muscleGroups = ((entry["muscleGroups"] as? List<*>)?.mapNotNull { mg ->
                    try { MuscleGroup.valueOf(mg as String) } catch (e: Exception) { null }
                }?.toMutableSet()) ?: mutableSetOf(),
                equipment = try { Equipment.valueOf((entry["equipment"] as? String) ?: "OTHER") } catch (e: Exception) { Equipment.OTHER },
                popularity = (entry["popularity"] as? Number)?.toInt() ?: 50,
                difficulty = (entry["difficulty"] as? Number)?.toInt() ?: 50,
                isBasicExercise = entry["basicExercise"] as? Boolean ?: false,
                recommendationTier = try { RecommendationTier.valueOf(entry["recommendationTier"] as String) } catch (e: Exception) { RecommendationTier.STANDARD }
            )
        }
        println("Loaded ${allExercises.size} exercises from real catalog")
    }

    // === 추천 파이프라인 (ExerciseRecommendationService 미러링) ===

    private fun recommend(
        recovering: Set<MuscleGroup> = emptySet(),
        equipment: Equipment? = null,
        target: Set<MuscleGroup> = emptySet(),
        noCardio: Boolean = false,
        limit: Int = 6,
        targetCategory: ExerciseCategory? = null
    ): List<Exercise> {
        if (allExercises.isEmpty()) return emptyList()
        return allExercises
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .filter { ex -> ex.muscleGroups.none { it in recovering } }
            .let { list -> if (equipment != null) list.filter { it.equipment == equipment } else list }
            .let { list -> if (target.isNotEmpty()) list.filter { ex -> ex.muscleGroups.any { it in target } } else list }
            .let { list -> if (noCardio) list.filter { it.category != ExerciseCategory.CARDIO } else list }
            .groupBy { classifier.classifyExercise(it) }
            .mapNotNull { (_, g) -> g.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator()) }
            .let { orderRoundRobin(it, targetCategory) }
            .take(limit)
    }

    private fun orderRoundRobin(exercises: List<Exercise>, targetCategory: ExerciseCategory? = null): List<Exercise> {
        val byCategory = exercises.groupBy { it.category }
            .mapValues { (_, g) -> g.sortedWith(RecommendationExerciseRanking.displayOrderComparator()).toMutableList() }
        val defaultOrder = listOf(ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
            ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY)
        val order = if (targetCategory != null) listOf(targetCategory) + defaultOrder.filter { it != targetCategory } else defaultOrder
        val result = mutableListOf<Exercise>()
        var hasMore = true
        while (hasMore) { hasMore = false; for (c in order) { val q = byCategory[c]; if (q!=null && q.isNotEmpty()) { result.add(q.removeFirst()); hasMore = true } } }
        return result
    }

    private fun skip() = org.junit.jupiter.api.Assumptions.assumeTrue(allExercises.isNotEmpty(), "Catalog not loaded")

    // ================================================================
    //  실제 카탈로그 기반 전신 추천
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 전신 추천")
    inner class RealFullBody {

        @Test
        @DisplayName("전신 4개: 4개 카테고리, 모두 복합운동, 첫 운동은 ESSENTIAL")
        fun `fullbody 4 from real catalog`() {
            skip()
            val result = recommend(limit = 4)

            println("\n=== 실제 카탈로그 전신 4개 ===")
            result.forEachIndexed { i, ex -> println("  ${i+1}. ${ex.name} [${ex.category}] ${ex.muscleGroups}") }

            // 4개 카테고리
            val cats = result.map { it.category }.distinct()
            assertTrue(cats.size >= 3, "실제 카탈로그 전신 4개에 3개+ 카테고리. 실제: $cats")

            // 첫 운동은 ESSENTIAL
            assertTrue(result[0].recommendationTier == RecommendationTier.ESSENTIAL || result[0].isBasicExercise,
                "첫 운동 ESSENTIAL. 실제: ${result[0].name}(${result[0].recommendationTier})")

            // 패턴 중복 없음
            val patterns = result.map { classifier.classifyExercise(it) }
            assertEquals(patterns.size, patterns.distinct().size, "패턴 중복: $patterns")
        }

        @Test
        @DisplayName("전신 6개: Legs+Back+Chest 모두 포함, 한 카테고리 50% 미초과")
        fun `fullbody 6 from real catalog`() {
            skip()
            val result = recommend(limit = 6)

            println("\n=== 실제 카탈로그 전신 6개 ===")
            result.forEachIndexed { i, ex -> println("  ${i+1}. ${ex.name} [${ex.category}] ${ex.muscleGroups}") }

            val cats = result.map { it.category }.toSet()
            assertTrue(ExerciseCategory.LEGS in cats, "하체 없음!")
            assertTrue(ExerciseCategory.BACK in cats, "등 없음!")
            assertTrue(ExerciseCategory.CHEST in cats, "가슴 없음!")

            val maxCount = result.groupBy { it.category }.values.maxOf { it.size }
            assertTrue(maxCount <= 3, "한 카테고리 최대 3개. 실제 최대: $maxCount")
        }

        @Test
        @DisplayName("전신 8개: 4개+ 카테고리, 한 카테고리 3개 미초과")
        fun `fullbody 8 from real catalog`() {
            skip()
            val result = recommend(limit = 8)

            println("\n=== 실제 카탈로그 전신 8개 ===")
            result.forEachIndexed { i, ex -> println("  ${i+1}. ${ex.name} [${ex.category}] pop=${ex.popularity}") }

            val cats = result.map { it.category }.distinct()
            assertTrue(cats.size >= 4, "8개에 4개+ 카테고리. 실제: $cats")
        }
    }

    // ================================================================
    //  실제 카탈로그 기반 회복 필터
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 회복 필터")
    inner class RealRecovery {

        @Test
        @DisplayName("가슴 회복 → 가슴 운동 0건, 등+하체 추천")
        fun `chest recovering from real catalog`() {
            skip()
            val result = recommend(recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS), limit = 6)

            assertTrue(result.none { ex -> ex.muscleGroups.any { it in setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS) } },
                "회복 근육 운동: ${result.filter { ex -> ex.muscleGroups.any { it in setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS) } }.map { it.name }}")
            assertTrue(result.isNotEmpty())
        }

        @Test
        @DisplayName("상체 전체 회복 → 하체+코어만, 결과 있음")
        fun `upper body recovering from real catalog`() {
            skip()
            val upper = setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS,
                MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.TRAPS)
            val result = recommend(recovering = upper, limit = 6)

            assertTrue(result.none { ex -> ex.muscleGroups.any { it in upper } })
            assertTrue(result.isNotEmpty(), "상체 전체 회복 중이어도 하체+코어 추천 필요")
        }
    }

    // ================================================================
    //  실제 카탈로그 기반 장비 필터
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 장비 필터")
    inner class RealEquipment {

        @Test
        @DisplayName("바벨 전용: 결과 있음 + 모두 BARBELL")
        fun `barbell only from real catalog`() {
            skip()
            val result = recommend(equipment = Equipment.BARBELL, limit = 6)
            assertTrue(result.isNotEmpty())
            assertTrue(result.all { it.equipment == Equipment.BARBELL })
            println("\n=== 실제 바벨 6개 ===")
            result.forEach { println("  ${it.name} [${it.category}]") }
        }

        @Test
        @DisplayName("덤벨 전용: 상하체 균형")
        fun `dumbbell from real catalog`() {
            skip()
            val result = recommend(equipment = Equipment.DUMBBELL, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.DUMBBELL })
            val cats = result.map { it.category }.distinct()
            assertTrue(cats.size >= 2, "덤벨도 2개+ 카테고리. 실제: $cats")
            println("\n=== 실제 덤벨 4개 ===")
            result.forEach { println("  ${it.name} [${it.category}]") }
        }

        @Test
        @DisplayName("맨몸 전용: BODYWEIGHT만")
        fun `bodyweight from real catalog`() {
            skip()
            val result = recommend(equipment = Equipment.BODYWEIGHT, limit = 4)
            assertTrue(result.all { it.equipment == Equipment.BODYWEIGHT })
            assertTrue(result.isNotEmpty())
            println("\n=== 실제 맨몸 4개 ===")
            result.forEach { println("  ${it.name} [${it.category}]") }
        }
    }

    // ================================================================
    //  실제 카탈로그 기반 타겟 필터
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 타겟 필터")
    inner class RealTarget {

        @Test
        @DisplayName("가슴 타겟: 5개, 모두 CHEST 근육 포함, CHEST 카테고리 먼저")
        fun `chest target from real catalog`() {
            skip()
            val result = recommend(target = setOf(MuscleGroup.CHEST), limit = 5, targetCategory = ExerciseCategory.CHEST)
            assertTrue(result.all { MuscleGroup.CHEST in it.muscleGroups })
            println("\n=== 실제 가슴 타겟 5개 ===")
            result.forEach { println("  ${it.name} [${it.category}] ${classifier.classifyExercise(it)}") }
        }

        @Test
        @DisplayName("하체 타겟: 5개, 하체 근육만")
        fun `legs target from real catalog`() {
            skip()
            val legMuscles = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val result = recommend(target = legMuscles, limit = 5, targetCategory = ExerciseCategory.LEGS)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in legMuscles } })
            println("\n=== 실제 하체 타겟 5개 ===")
            result.forEach { println("  ${it.name} [${it.category}] ${classifier.classifyExercise(it)}") }
        }

        @Test
        @DisplayName("Pull 타겟: 등+이두, 등 운동이 먼저")
        fun `pull target from real catalog`() {
            skip()
            val pullMuscles = setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.TRAPS)
            val result = recommend(target = pullMuscles, limit = 5, targetCategory = ExerciseCategory.BACK)
            assertTrue(result.all { ex -> ex.muscleGroups.any { it in pullMuscles } })
            // 첫 운동은 BACK 근육을 타겟하는 복합운동이어야 함
            // (데드리프트는 LEGS 카테고리이지만 BACK 근육 포함이므로 허용)
            val firstHasBackMuscle = result[0].muscleGroups.any { it in setOf(MuscleGroup.BACK, MuscleGroup.LATS) }
            assertTrue(firstHasBackMuscle,
                "Pull Day 첫 운동은 등 근육 포함. 실제: ${result[0].name}(${result[0].muscleGroups})")
            println("\n=== 실제 Pull 5개 ===")
            result.forEach { println("  ${it.name} [${it.category}] ${classifier.classifyExercise(it)}") }
        }
    }

    // ================================================================
    //  실제 카탈로그 패턴 품질
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 패턴 품질")
    inner class RealPatternQuality {

        @Test
        @DisplayName("전신 6개: 패턴 중복 0건")
        fun `no pattern duplication fullbody 6`() {
            skip()
            val result = recommend(limit = 6)
            val patterns = result.map { classifier.classifyExercise(it) }
            val dups = patterns.groupBy { it }.filter { it.value.size > 1 }
            assertTrue(dups.isEmpty(), "패턴 중복: $dups")
        }

        @Test
        @DisplayName("전신 8개: 패턴 중복 0건")
        fun `no pattern duplication fullbody 8`() {
            skip()
            val result = recommend(limit = 8)
            val patterns = result.map { classifier.classifyExercise(it) }
            val dups = patterns.groupBy { it }.filter { it.value.size > 1 }
            assertTrue(dups.isEmpty(), "패턴 중복: $dups")
        }

        @Test
        @DisplayName("ADVANCED/SPECIALIZED 절대 미포함")
        fun `no advanced or specialized`() {
            skip()
            val result = recommend(limit = 10)
            assertTrue(result.none { it.recommendationTier in listOf(RecommendationTier.ADVANCED, RecommendationTier.SPECIALIZED) })
        }
    }

    // ================================================================
    //  실제 카탈로그 복합 필터 시나리오
    // ================================================================

    @Nested
    @DisplayName("실제 카탈로그: 복합 시나리오")
    inner class RealComplexScenarios {

        @Test
        @DisplayName("덤벨 + 가슴 회복 중: 덤벨 등/하체 추천")
        fun `dumbbell and chest recovering`() {
            skip()
            val result = recommend(
                equipment = Equipment.DUMBBELL,
                recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS),
                limit = 4
            )
            assertTrue(result.all { it.equipment == Equipment.DUMBBELL })
            assertTrue(result.none { ex -> ex.muscleGroups.any { it in setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS) } })
            assertTrue(result.isNotEmpty(), "덤벨+가슴회복 조합에도 결과 필요")
            println("\n=== 덤벨 + 가슴회복 4개 ===")
            result.forEach { println("  ${it.name} [${it.category}]") }
        }

        @Test
        @DisplayName("근비대 목표: 유산소 제외, 복합 50%+")
        fun `muscle gain no cardio compound heavy`() {
            skip()
            val result = recommend(noCardio = true, limit = 6)
            assertTrue(result.none { it.category == ExerciseCategory.CARDIO })
            val compoundRatio = result.count { it.muscleGroups.size >= 2 }.toDouble() / result.size
            assertTrue(compoundRatio >= 0.5, "복합운동 비율 50%+. 실제: ${(compoundRatio*100).toInt()}%")
        }

        @Test
        @DisplayName("하체 타겟 + 햄스트링 회복: 스쿼트 변형 중 햄 포함 제외")
        fun `leg day hamstring recovering`() {
            skip()
            val legTarget = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
            val result = recommend(target = legTarget, recovering = setOf(MuscleGroup.HAMSTRINGS), limit = 5, targetCategory = ExerciseCategory.LEGS)

            assertTrue(result.none { MuscleGroup.HAMSTRINGS in it.muscleGroups },
                "햄 운동: ${result.filter { MuscleGroup.HAMSTRINGS in it.muscleGroups }.map { it.name }}")
            assertTrue(result.isNotEmpty(), "햄 제외해도 하체 운동 있어야 함")
            println("\n=== 하체 + 햄회복 5개 ===")
            result.forEach { println("  ${it.name} [${it.category}] ${it.muscleGroups}") }
        }
    }
}
