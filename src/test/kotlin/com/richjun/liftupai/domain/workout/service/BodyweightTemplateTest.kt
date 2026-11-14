package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.util.ExerciseRequirements
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** 맨몸 중급·상급 템플릿과 304 재현 — 2026-11-14 coach-autopilot-r1 */
class BodyweightTemplateTest {
    private val exercises = RecoFitEvalTest.loadExercises()

    private fun profile(level: String, days: Int, minutes: Int, equipment: Set<String>, age: Int, goals: List<String> = listOf("bulk_up", "strength")) =
        TemplateRecommendationEngine.RecoProfile(
            experience = level, goals = goals, weeklyDays = days, sessionDuration = minutes,
            equipment = equipment, injuries = emptySet(), focus = FocusArea.resolve(goals), age = age,
        )

    private fun recommend(p: TemplateRecommendationEngine.RecoProfile) = RecoFitEvalTest.buildEngine(exercises, p.injuries).recommend(p)

    @Test
    @DisplayName("맨몸 중급·상급 템플릿이 있고, 모든 운동이 기구 없이(또는 철봉으로) 가능하며 날마다 등 위쪽 운동이 있다")
    fun templatesExistAndAreBodyweight() {
        val bySlug = exercises.associateBy { it.slug }
        listOf("home_bodyweight_intermediate" to "intermediate", "home_bodyweight_advanced" to "advanced").forEach { (code, level) ->
            val t = PresetTemplates.ALL.firstOrNull { it.code == code } ?: error("$code 없음")
            assertEquals(level, t.targetExperience)
            t.days.forEach { d ->
                val list = d.exercises.map { bySlug.getValue(it.slug) }
                assertTrue(list.all { it.equipment?.name == "BODYWEIGHT" }, "$code day ${d.dayNumber} 맨몸이 아닌 운동")
                assertTrue(list.all { ExerciseRequirements.allows(it, setOf("BODYWEIGHT", "OTHER")) }, "$code day ${d.dayNumber} 철봉으로도 안 되는 운동")
                if (d.workoutType.name in PlanInvariants.BACK_DAY_TYPES) {
                    assertTrue(list.any { PlanInvariants.isUpperBack(it) && ExerciseRequirements.allows(it, setOf("BODYWEIGHT")) },
                        "$code day ${d.dayNumber} 기구 없이 하는 등 위쪽 운동이 없다")
                }
            }
        }
    }

    @Test
    @DisplayName("304 재현(전문가·39세·집·기구 없음·주 4일·30분): 상급 맨몸 템플릿이 뽑히고, 턱걸이는 빠지고 불변 조건을 지킨다")
    fun replay304() {
        val p = profile("expert", 4, 30, setOf("BODYWEIGHT"), 39)
        val out = recommend(p)
        assertEquals("home_bodyweight_advanced", out.best.template.code, out.ranked.take(3).joinToString { "${it.template.code}=${"%.1f".format(it.total)}" })
        val slugs = out.adapted.flatMap { d -> d.exercises.map { it.exercise.slug } }
        assertFalse(slugs.any { it.startsWith("pull-up") || it == "chin-up" || it == "inverted-row" }, "기구 없는 사람에게 철봉·낮은 바 동작: $slugs")
        val days = out.adapted.map { d -> PlanInvariants.Day(d.dayNumber, d.workoutType, d.exercises.map { it.exercise }) }
        assertTrue(PlanInvariants.check(days, p.equipment).isEmpty(), "${PlanInvariants.check(days, p.equipment)}")
        assertTrue("archer-push-up" in slugs, "상급자는 아처 푸시업 같은 상위 변형을 받아야 한다: $slugs")
    }

    @Test
    @DisplayName("철봉이 있는 상급자는 같은 템플릿에서 턱걸이를 받는다")
    fun advancedWithBarKeepsPullUps() {
        val out = recommend(profile("advanced", 4, 45, setOf("BODYWEIGHT", "OTHER"), 31, listOf("wide_back", "bulk_up")))
        assertEquals("home_bodyweight_advanced", out.best.template.code)
        val slugs = out.adapted.flatMap { d -> d.exercises.map { it.exercise.slug } }
        assertTrue(slugs.any { it == "pull-up-normal-grip" || it == "chin-up" }, "턱걸이가 빠졌다: $slugs")
    }

    @Test
    @DisplayName("68세 중급 맨몸: 중급 템플릿이고 아처·폭발 동작이 없다")
    fun olderIntermediateIsSafe() {
        val out = recommend(profile("intermediate", 2, 30, setOf("BODYWEIGHT"), 68, listOf("general_fitness")))
        assertEquals("intermediate", out.best.template.targetExperience)
        val slugs = out.adapted.flatMap { d -> d.exercises.map { it.exercise.slug } }
        assertTrue(slugs.none { "archer" in it || it == "burpee" || it.startsWith("jump") }, "$slugs")
    }
}
