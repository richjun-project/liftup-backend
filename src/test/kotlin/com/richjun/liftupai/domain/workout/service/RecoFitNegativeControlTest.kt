package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.service.new.RecoRules

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import com.richjun.liftupai.domain.workout.service.RecoFitChecks.Day
import com.richjun.liftupai.domain.workout.service.RecoFitChecks.Item
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 음성 대조: RecoFitEvalTest 의 새 하드 체크가 "실제로 실패할 수 있음"을 위반 사례로 보인다.
 * 각 체크마다 (1) 일부러 어긴 플랜에서 실패하고 (2) 같은 구조의 올바른 플랜에서는 통과해야 한다.
 * 체크가 항상 통과하는 빈 껍데기라면 RecoFitEvalTest 의 전원 통과는 아무 의미가 없다.
 */
class RecoFitNegativeControlTest {
    private var nextId = 1L

    private fun ex(
        slug: String, pattern: String, cat: ExerciseCategory, eq: Equipment, vararg muscles: MuscleGroup,
        difficulty: Int = 40, tier: RecommendationTier = RecommendationTier.STANDARD, basic: Boolean = false,
    ) = Exercise(
        id = nextId++, slug = slug, name = slug, category = cat, movementPattern = pattern, equipment = eq,
        muscleGroups = muscles.toMutableSet(), difficulty = difficulty, recommendationTier = tier, isBasicExercise = basic, isPlanEligible = true,
    )

    private val squat = ex("barbell-full-squat", "SQUAT", ExerciseCategory.LEGS, Equipment.BARBELL, MuscleGroup.QUADRICEPS, MuscleGroup.GLUTES,
        difficulty = 50, tier = RecommendationTier.ESSENTIAL, basic = true)
    private val bench = ex("barbell-bench-press", "HORIZONTAL_PRESS_BARBELL", ExerciseCategory.CHEST, Equipment.BARBELL, MuscleGroup.CHEST,
        difficulty = 50, tier = RecommendationTier.ESSENTIAL, basic = true)
    private val pullUp = ex("pull-up-normal-grip", "PULLUP_CHINUP", ExerciseCategory.BACK, Equipment.BODYWEIGHT, MuscleGroup.BACK, difficulty = 45)
    private val curl = ex("dumbbell-curls", "BICEP_CURL_DUMBBELL", ExerciseCategory.ARMS, Equipment.DUMBBELL, MuscleGroup.BICEPS)
    private val glute = ex("glute-bridge", "GLUTE_FOCUSED", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, MuscleGroup.GLUTES, difficulty = 20)
    private val snatch = ex("barbell-snatch-deadlift", "SNATCH", ExerciseCategory.BACK, Equipment.BARBELL, MuscleGroup.BACK, difficulty = 80)

    private fun item(e: Exercise, sets: Int = 3, min: Int = 8, max: Int = 12, rest: Int = 90) = Item(e, sets, min, max, rest)
    private fun day(vararg items: Item) = Day(1, "FULL_BODY", items.toList())

    @Test
    @DisplayName("시간 적합: 90분을 요청했는데 25분짜리 하루는 실패하고, 요청에 맞는 하루는 통과한다")
    fun minuteFitFailsOnShortDay() {
        val short = listOf(day(item(squat, sets = 3), item(curl, sets = 3), item(glute, sets = 3)))
        val failures = RecoFitChecks.minuteFit(short, 90)
        assertTrue(failures.isNotEmpty(), "25분짜리 하루가 90분 요청을 통과했다")
        assertTrue(failures.single().contains("%"), "퍼센트로 설명해야 한다: $failures")

        // 같은 하루를 30분 요청으로 보면 적합 구간(85~110%) 안
        val seconds = RecoFitChecks.seconds(short.single())
        val minutes = (seconds / 60.0).toInt()
        assertTrue(RecoFitChecks.minuteFit(short, minutes + 1).isEmpty() || RecoFitChecks.minuteFit(short, minutes).isEmpty(),
            "양성 대조: 요청과 맞는 하루는 통과해야 한다 (est=${seconds}s)")
    }

    @Test
    @DisplayName("시간 적합: 30분을 요청했는데 2배 길이의 하루도 실패한다")
    fun minuteFitFailsOnLongDay() {
        val long = listOf(day(*Array(8) { item(squat, sets = 5, rest = 180) }))
        assertTrue(RecoFitChecks.minuteFit(long, 30).isNotEmpty())
    }

    @Test
    @DisplayName("바벨 무게: 15kg 바벨 벤치는 실패하고, 20kg 이상·무게 없음·덤벨 12.5kg 은 통과한다")
    fun barbellWeightFailsBelowBar() {
        assertTrue(RecoFitChecks.barbellWeights(listOf(bench to 15.0)).isNotEmpty(), "바벨 15kg 가 통과했다")
        assertTrue(RecoFitChecks.barbellWeights(listOf(squat to 2.5)).isNotEmpty())
        assertTrue(RecoFitChecks.barbellWeights(listOf(bench to 20.0, squat to 62.5, curl to 12.5, glute to 0.0)).isEmpty())
    }

    @Test
    @DisplayName("적합성: 철봉 없는 사람의 풀업과 초보의 스내치 데드리프트는 실패하고, 철봉이 있거나 수준에 맞으면 통과한다")
    fun suitabilityFails() {
        val plan = listOf(day(item(pullUp), item(snatch)))
        val failures = RecoFitChecks.suitability(plan, setOf("DUMBBELL", "BODYWEIGHT"), ExperienceLevel.BEGINNER, 30)
        assertTrue(failures.any { it.contains("철봉") }, "철봉 없는 풀업이 통과했다: $failures")
        assertTrue(failures.any { it.contains("너무 어려운") }, "초보의 스내치가 통과했다: $failures")

        // 양성 대조: 철봉(OTHER)이 있고 고급자면 풀업·스내치 데드가 허용된다
        assertTrue(RecoFitChecks.suitability(plan, setOf("OTHER", "BARBELL"), ExperienceLevel.ADVANCED, 30).isEmpty())
        // 노비스도 초보처럼 본다 — ExerciseSuitability 가 NOVICE 를 초보로 보지 않아도 체크는 엄격하다
        assertTrue(RecoFitChecks.suitability(listOf(day(item(snatch))), setOf("BARBELL"), ExperienceLevel.NOVICE, 30).isNotEmpty())
        // 고령자는 수준과 무관하게 폭발적 동작이 막힌다
        assertTrue(RecoFitChecks.suitability(listOf(day(item(snatch))), setOf("BARBELL"), ExperienceLevel.ADVANCED, 70).isNotEmpty())
    }

    @Test
    @DisplayName("초보 처방: 초보의 바벨 스쿼트 5x5 는 실패하고, 3x8-12 는 통과하며, 중급자의 5x5 는 허용된다")
    fun beginnerPrescriptionFails() {
        val heavy = listOf(day(item(squat, sets = 5, min = 5, max = 5, rest = 180)))
        val failures = RecoFitChecks.beginnerPrescription(heavy, ExperienceLevel.BEGINNER)
        assertEquals(2, failures.size, "반복 8 미만과 세트 3 초과가 둘 다 잡혀야 한다: $failures")
        assertTrue(RecoFitChecks.beginnerPrescription(heavy, ExperienceLevel.NOVICE).isNotEmpty())

        assertTrue(RecoFitChecks.beginnerPrescription(listOf(day(item(squat, sets = 3, min = 8, max = 12, rest = 120))), ExperienceLevel.BEGINNER).isEmpty())
        assertTrue(RecoFitChecks.beginnerPrescription(heavy, ExperienceLevel.INTERMEDIATE).isEmpty(), "중급자에게는 걸지 않는다")
    }

    @Test
    @DisplayName("반복: 같은 운동이 주 3회 나오는데 바꿀 후보가 있으면 실패하고, 후보가 없으면 면제된다")
    fun repeatsFailWhenAlternativesExist() {
        val other = ex("hip-thrust", "GLUTE_FOCUSED", ExerciseCategory.LEGS, Equipment.BODYWEIGHT, MuscleGroup.GLUTES)
        val plan = listOf(
            Day(1, "LOWER", listOf(item(glute))), Day(2, "LOWER", listOf(item(glute))), Day(3, "LOWER", listOf(item(glute))),
        )
        assertTrue(RecoFitChecks.repeats(plan, { _, _ -> listOf(other) }).isNotEmpty(), "후보가 있는데 3회 반복이 통과했다")
        assertTrue(RecoFitChecks.repeats(plan, { _, _ -> emptyList() }).isEmpty(), "후보가 없으면 면제")
        // 주 2회는 허용
        assertTrue(RecoFitChecks.repeats(plan.take(2), { _, _ -> listOf(other) }).isEmpty())
    }

    @Test
    @DisplayName("집중 부위: 하체 집중인데 주간 하체 운동이 1개면 실패한다")
    fun focusVolumeFails() {
        val plan = listOf(Day(1, "UPPER", listOf(item(bench), item(curl))), Day(2, "LOWER", listOf(item(squat))))
        assertTrue(RecoFitChecks.focusVolume(plan, listOf(FocusArea.LEGS), 2).isNotEmpty())
        assertTrue(RecoFitChecks.focusVolume(plan, listOf(FocusArea.LEGS), 1).isEmpty())
    }

    @Test
    @DisplayName("선두 운동: 대표 복합운동이 있는데 보조 운동으로 시작하는 날은 실패하고, 대표 복합운동이 아예 없는 날은 면제된다")
    fun leadOrderingFails() {
        assertTrue(RecoFitChecks.leadsWithEssentialCompound(day(item(squat), item(curl))))
        assertTrue(!RecoFitChecks.leadsWithEssentialCompound(day(item(curl), item(squat))))
        assertTrue(RecoFitChecks.leadOrdering(listOf(day(item(curl), item(squat)))).isNotEmpty(), "순서 위반이 통과했다")
        assertTrue(RecoFitChecks.leadOrdering(listOf(day(item(squat), item(curl)))).isEmpty())
        // 대표 복합운동이 하루에 없으면(맨몸 템플릿) 면제
        assertTrue(RecoFitChecks.leadOrdering(listOf(day(item(curl), item(glute)))).isEmpty())
        assertTrue(!RecoFitChecks.leadsWithCompound(day(item(curl), item(squat))))
    }

    @Test
    @DisplayName("실제 어댑터가 만든 플랜은 같은 체크를 통과한다(체크가 어댑터와 같은 정의를 쓴다는 확인)")
    fun adapterOutputPassesTheSameChecks() {
        val exercises = RecoFitEvalTest.loadExercises()
        val bySlug = exercises.associateBy { it.slug }
        val pool = exercises.filter { it.isPlanEligible }.groupBy { it.category }.mapValues { (_, v) -> v.sortedWith(compareBy({ -it.popularity }, { it.id })) }
        val adapter = TemplatePlanAdapter({ pool[it].orEmpty() }, { false })
        fun src(slug: String, sets: Int, min: Int, max: Int) = TemplatePlanAdapter.SourceExercise(bySlug.getValue(slug), sets, min, max, 120, true, 8.0)
        val days = listOf(
            TemplatePlanAdapter.SourceDay(1, "FULL_BODY", listOf(src("barbell-full-squat", 5, 5, 5), src("barbell-bench-press", 5, 5, 5), src("cable-pulldown", 4, 6, 8))),
        )
        val ctx = TemplatePlanAdapter.Context(
            sessionMinutes = 60, level = ExperienceLevel.BEGINNER, age = 30,
            suitable = RecoRules.suitability(emptySet(), ExperienceLevel.BEGINNER, 30),
        )
        val adapted = RecoFitChecks.fromAdapted(adapter.adapt(days, ctx))
        assertTrue(RecoFitChecks.beginnerPrescription(adapted, ExperienceLevel.BEGINNER).isEmpty(), "어댑터가 초보 처방을 못 고쳤다")
        assertTrue(RecoFitChecks.minuteFit(adapted, 60).isEmpty(), "어댑터가 시간을 못 맞췄다: ${adapted.map { RecoFitChecks.seconds(it) / 60 }}분")
    }
}
