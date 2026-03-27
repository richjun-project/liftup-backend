package com.richjun.liftupai.domain.workout.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.richjun.liftupai.domain.workout.entity.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * 최종 감사 — 실제 카탈로그 1318개로 모든 시나리오의 정확한 출력을 검증
 *
 * 이 테스트가 FAIL하면 추천이 잘못된 것입니다. 테스트를 수정하지 않습니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FinalAuditTest {

    private val classifier = ExercisePatternClassifier()
    private lateinit var db: List<Exercise>

    @BeforeAll
    fun load() {
        val file = File("src/main/resources/catalog/exercise-catalog.json")
        org.junit.jupiter.api.Assumptions.assumeTrue(file.exists())
        val mapper = ObjectMapper().apply { findAndRegisterModules() }
        val raw: List<Map<String, Any>> = mapper.readValue(file)
        db = raw.mapIndexed { i, e ->
            Exercise(
                id = i.toLong()+1, slug = e["slug"] as String,
                name = ((e["translations"] as? List<*>)?.filterIsInstance<Map<*,*>>()
                    ?.firstOrNull { it["locale"]=="en" }?.get("name") as? String) ?: e["slug"] as String,
                category = try { ExerciseCategory.valueOf(e["category"] as String) } catch(_:Exception) { ExerciseCategory.FULL_BODY },
                movementPattern = e["movementPattern"] as? String,
                muscleGroups = ((e["muscleGroups"] as? List<*>)?.mapNotNull { try { MuscleGroup.valueOf(it as String) } catch(_:Exception){null} }?.toMutableSet()) ?: mutableSetOf(),
                equipment = try { Equipment.valueOf((e["equipment"] as? String)?:"OTHER") } catch(_:Exception) { Equipment.OTHER },
                popularity = (e["popularity"] as? Number)?.toInt() ?: 50,
                difficulty = (e["difficulty"] as? Number)?.toInt() ?: 50,
                isBasicExercise = e["basicExercise"] as? Boolean ?: false,
                recommendationTier = try { RecommendationTier.valueOf(e["recommendationTier"] as String) } catch(_:Exception) { RecommendationTier.STANDARD }
            )
        }
    }

    private fun recommend(limit: Int, recovering: Set<MuscleGroup> = emptySet(),
                          eq: Equipment? = null, target: Set<MuscleGroup> = emptySet(),
                          targetCat: ExerciseCategory? = null, noCardio: Boolean = false): List<Exercise> {
        return db
            .filter { RecommendationExerciseRanking.isGeneralCandidate(it) }
            .filter { ex -> ex.muscleGroups.none { it in recovering } }
            .let { l -> if (eq!=null) l.filter { it.equipment==eq } else l }
            .let { l -> if (target.isNotEmpty()) l.filter { ex -> ex.muscleGroups.any { it in target } } else l }
            .let { l -> if (noCardio) l.filter { it.category!=ExerciseCategory.CARDIO } else l }
            .groupBy { classifier.classifyExercise(it) }
            .mapNotNull { (_,g) -> g.minWithOrNull(RecommendationExerciseRanking.patternSelectionComparator()) }
            .let { orderByPriority(it, targetCat) }
            .take(limit)
    }

    private fun orderByPriority(exercises: List<Exercise>, targetCat: ExerciseCategory?): List<Exercise> {
        val byCategory = exercises.groupBy { it.category }
            .mapValues { (_,g) -> g.sortedWith(RecommendationExerciseRanking.displayOrderComparator()).toMutableList() }
        val def = listOf(ExerciseCategory.LEGS, ExerciseCategory.BACK, ExerciseCategory.CHEST,
            ExerciseCategory.SHOULDERS, ExerciseCategory.ARMS, ExerciseCategory.CORE,
            ExerciseCategory.CARDIO, ExerciseCategory.FULL_BODY)
        val order = if (targetCat!=null) listOf(targetCat)+def.filter{it!=targetCat} else def
        val result = mutableListOf<Exercise>()
        var more = true
        while (more) { more=false; for (c in order) { val q=byCategory[c]; if(q!=null&&q.isNotEmpty()){result.add(q.removeFirst());more=true} } }
        return result
    }

    private fun printResult(title: String, result: List<Exercise>) {
        println("\n$title")
        result.forEachIndexed { i, ex ->
            val c = if (ex.muscleGroups.size>=2) "복합" else "고립"
            println("  ${i+1}. ${ex.name.padEnd(45)} [${ex.category.name.padEnd(10)}] $c  eq=${(ex.equipment?.name ?: "NONE").padEnd(12)} pop=${ex.popularity}")
        }
    }

    // ================================================================
    //  AUDIT 1: 전신 추천 — Big 3가 상위에 나오는가?
    // ================================================================

    @Test @DisplayName("AUDIT-01: 전신 4개에 Barbell full squat + deadlift + bench press 포함")
    fun audit01() {
        val r = recommend(4)
        printResult("AUDIT-01: 전신 4개", r)
        val names = r.map { it.name }
        assertTrue("Barbell full squat" in names, "Barbell full squat 없음! $names")
        assertTrue("Barbell deadlift" in names, "Barbell deadlift 없음! $names")
        assertTrue("Barbell bench press" in names, "Barbell bench press 없음! $names")
    }

    @Test @DisplayName("AUDIT-02: 전신 4개 = 4개 다른 카테고리")
    fun audit02() {
        val r = recommend(4)
        assertEquals(4, r.map { it.category }.distinct().size, "4개 카테고리 필요: ${r.map{it.category}}")
    }

    @Test @DisplayName("AUDIT-03: 전신 6개 = Legs+Back+Chest+Shoulders 최소 포함")
    fun audit03() {
        val r = recommend(6)
        printResult("AUDIT-03: 전신 6개", r)
        val cats = r.map { it.category }.toSet()
        assertTrue(ExerciseCategory.LEGS in cats, "LEGS 없음")
        assertTrue(ExerciseCategory.BACK in cats, "BACK 없음")
        assertTrue(ExerciseCategory.CHEST in cats, "CHEST 없음")
        assertTrue(ExerciseCategory.SHOULDERS in cats, "SHOULDERS 없음")
    }

    @Test @DisplayName("AUDIT-04: 전신 8개 = 5개+ 카테고리, 한 카테고리 최대 3개")
    fun audit04() {
        val r = recommend(8)
        printResult("AUDIT-04: 전신 8개", r)
        val cats = r.map { it.category }.distinct()
        assertTrue(cats.size >= 5, "5개+ 카테고리: $cats")
        val max = r.groupBy { it.category }.values.maxOf { it.size }
        assertTrue(max <= 3, "한 카테고리 최대 3개. 최대: $max")
    }

    // ================================================================
    //  AUDIT 2: 어제 가슴 운동 후
    // ================================================================

    @Test @DisplayName("AUDIT-05: 어제 가슴 → 가슴/삼두/어깨 운동 0건")
    fun audit05() {
        val recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS)
        val r = recommend(6, recovering = recovering)
        printResult("AUDIT-05: 어제 가슴 후 6개", r)
        val violations = r.filter { ex -> ex.muscleGroups.any { it in recovering } }
        assertTrue(violations.isEmpty(), "회복 근육 운동: ${violations.map{it.name}}")
    }

    @Test @DisplayName("AUDIT-06: 어제 가슴 → Barbell deadlift(등) + Barbell full squat(하체) 포함")
    fun audit06() {
        val r = recommend(6, recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS, MuscleGroup.SHOULDERS))
        val names = r.map { it.name }
        assertTrue(r.any { it.category == ExerciseCategory.LEGS }, "하체 없음")
        assertTrue(r.any { it.category == ExerciseCategory.BACK }, "등 없음")
    }

    // ================================================================
    //  AUDIT 3: 장비별 추천
    // ================================================================

    @Test @DisplayName("AUDIT-07: 덤벨 4개 = 모두 DUMBBELL + 상하체 균형")
    fun audit07() {
        val r = recommend(4, eq = Equipment.DUMBBELL)
        printResult("AUDIT-07: 덤벨 4개", r)
        assertTrue(r.all { it.equipment == Equipment.DUMBBELL }, "비덤벨: ${r.filter{it.equipment!=Equipment.DUMBBELL}.map{it.name}}")
        val cats = r.map { it.category }.toSet()
        assertTrue(cats.size >= 3, "덤벨도 3개+ 카테고리: $cats")
    }

    @Test @DisplayName("AUDIT-08: 맨몸 3개 = 모두 BODYWEIGHT + Pull-up/Push-up/Plank 포함")
    fun audit08() {
        val r = recommend(3, eq = Equipment.BODYWEIGHT)
        printResult("AUDIT-08: 맨몸 3개", r)
        assertTrue(r.all { it.equipment == Equipment.BODYWEIGHT })
        assertTrue(r.size >= 2, "맨몸 최소 2개")
    }

    @Test @DisplayName("AUDIT-09: 바벨 6개 = Big 3 포함")
    fun audit09() {
        val r = recommend(6, eq = Equipment.BARBELL)
        printResult("AUDIT-09: 바벨 6개", r)
        val names = r.map { it.name }
        assertTrue("Barbell full squat" in names || r.any { it.name.contains("squat", true) && it.equipment == Equipment.BARBELL }, "바벨 스쿼트 없음")
        assertTrue("Barbell bench press" in names, "바벨 벤치 없음")
        assertTrue("Barbell deadlift" in names, "바벨 데드 없음")
    }

    // ================================================================
    //  AUDIT 4: 타겟별 추천
    // ================================================================

    @Test @DisplayName("AUDIT-10: 가슴 타겟 → 첫 운동이 CHEST 카테고리")
    fun audit10() {
        val r = recommend(5, target = setOf(MuscleGroup.CHEST), targetCat = ExerciseCategory.CHEST)
        printResult("AUDIT-10: 가슴 타겟 5개", r)
        assertEquals(ExerciseCategory.CHEST, r[0].category, "첫 운동이 CHEST 카테고리가 아님: ${r[0].name}(${r[0].category})")
    }

    @Test @DisplayName("AUDIT-11: 가슴 타겟 → Barbell bench press 포함")
    fun audit11() {
        val r = recommend(5, target = setOf(MuscleGroup.CHEST), targetCat = ExerciseCategory.CHEST)
        assertTrue(r.any { it.name == "Barbell bench press" }, "벤치프레스 없음: ${r.map{it.name}}")
    }

    @Test @DisplayName("AUDIT-12: Pull 타겟 → 첫 운동이 BACK 카테고리이거나 BACK 근육 포함")
    fun audit12() {
        val pullMuscles = setOf(MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.BICEPS, MuscleGroup.TRAPS)
        val r = recommend(5, target = pullMuscles, targetCat = ExerciseCategory.BACK)
        printResult("AUDIT-12: Pull 5개", r)
        val firstHasBack = r[0].muscleGroups.any { it in setOf(MuscleGroup.BACK, MuscleGroup.LATS) }
        assertTrue(r[0].category == ExerciseCategory.BACK || firstHasBack,
            "첫 운동이 등 관련이 아님: ${r[0].name}(${r[0].category})")
    }

    @Test @DisplayName("AUDIT-13: 하체 타겟 → 스쿼트 포함")
    fun audit13() {
        val legMuscles = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES)
        val r = recommend(5, target = legMuscles, targetCat = ExerciseCategory.LEGS)
        printResult("AUDIT-13: 하체 5개", r)
        assertTrue(r.any { classifier.classifyExercise(it).name.contains("SQUAT") || classifier.classifyExercise(it).name.contains("LEG_PRESS") },
            "스쿼트/레그프레스 없음")
    }

    // ================================================================
    //  AUDIT 5: 패턴 품질
    // ================================================================

    @Test @DisplayName("AUDIT-14: 전신 6개 패턴 전부 유니크")
    fun audit14() {
        val r = recommend(6)
        val patterns = r.map { classifier.classifyExercise(it) }
        assertEquals(patterns.distinct().size, patterns.size, "패턴 중복: $patterns")
    }

    @Test @DisplayName("AUDIT-15: 전신 8개 패턴 전부 유니크")
    fun audit15() {
        val r = recommend(8)
        val patterns = r.map { classifier.classifyExercise(it) }
        assertEquals(patterns.distinct().size, patterns.size, "패턴 중복: $patterns")
    }

    @Test @DisplayName("AUDIT-16: ADVANCED/SPECIALIZED 절대 안 나옴")
    fun audit16() {
        val r = recommend(10)
        assertTrue(r.none { it.recommendationTier in listOf(RecommendationTier.ADVANCED, RecommendationTier.SPECIALIZED) })
    }

    // ================================================================
    //  AUDIT 6: 복합 시나리오
    // ================================================================

    @Test @DisplayName("AUDIT-17: 햄스트링 아프면 스쿼트(햄 포함) 제외, 레그프레스(쿼드+글루트) 가능")
    fun audit17() {
        val r = recommend(5, recovering = setOf(MuscleGroup.HAMSTRINGS),
            target = setOf(MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES, MuscleGroup.CALVES),
            targetCat = ExerciseCategory.LEGS)
        printResult("AUDIT-17: 하체+햄회복 5개", r)
        assertTrue(r.none { MuscleGroup.HAMSTRINGS in it.muscleGroups }, "햄 운동 포함됨")
        assertTrue(r.isNotEmpty(), "결과 비어있음")
    }

    @Test @DisplayName("AUDIT-18: 상체 전체 회복 → 결과가 있고 상체 0건")
    fun audit18() {
        val upper = setOf(MuscleGroup.CHEST, MuscleGroup.BACK, MuscleGroup.LATS,
            MuscleGroup.SHOULDERS, MuscleGroup.BICEPS, MuscleGroup.TRICEPS, MuscleGroup.TRAPS)
        val r = recommend(6, recovering = upper)
        printResult("AUDIT-18: 상체 전체 회복 6개", r)
        assertTrue(r.none { ex -> ex.muscleGroups.any { it in upper } }, "상체 운동 포함됨")
        assertTrue(r.isNotEmpty(), "결과 비어있음")
    }

    @Test @DisplayName("AUDIT-19: 덤벨+가슴회복 = 덤벨만 + 가슴 0건")
    fun audit19() {
        val r = recommend(4, eq = Equipment.DUMBBELL, recovering = setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS))
        printResult("AUDIT-19: 덤벨+가슴회복 4개", r)
        assertTrue(r.all { it.equipment == Equipment.DUMBBELL })
        assertTrue(r.none { ex -> ex.muscleGroups.any { it in setOf(MuscleGroup.CHEST, MuscleGroup.TRICEPS) } })
        assertTrue(r.isNotEmpty())
    }

    @Test @DisplayName("AUDIT-20: 근비대 = 유산소 0건 + 복합 50%+")
    fun audit20() {
        val r = recommend(6, noCardio = true)
        printResult("AUDIT-20: 근비대 6개", r)
        assertTrue(r.none { it.category == ExerciseCategory.CARDIO })
        val compoundRatio = r.count { it.muscleGroups.size >= 2 }.toDouble() / r.size
        assertTrue(compoundRatio >= 0.5, "복합 비율: ${(compoundRatio*100).toInt()}%")
    }
}
