package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/** 실제 카탈로그·프리셋 템플릿으로 보는 추천 규칙 (페르소나 전수는 RecoFitEvalTest) */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateRecommendationEngineTest {

    private lateinit var exercises: List<Exercise>

    @BeforeAll
    fun load() {
        exercises = RecoFitEvalTest.loadExercises()
    }

    private fun profile(
        experience: String = "beginner",
        goals: List<String> = listOf("general_fitness"),
        days: Int = 3,
        minutes: Int = 45,
        equipment: Set<String> = setOf("BARBELL", "DUMBBELL", "MACHINE", "CABLE", "BODYWEIGHT"),
        injuries: Set<String> = emptySet(),
    ) = TemplateRecommendationEngine.RecoProfile(
        experience, goals, days, minutes, equipment, injuries, FocusArea.resolve(goals)
    )

    private fun recommend(p: TemplateRecommendationEngine.RecoProfile) =
        RecoFitEvalTest.buildEngine(exercises, p.injuries).recommend(p)

    @Test
    fun `다이어트 목표 하나가 덮어쓰기가 아니다 - 홈트 초보는 바벨 서킷을 받지 않는다`() {
        // 2026-10-06 사고: 일본 사용자(초보·홈트)가 diet_circuit(바벨 스쿼트·벤치·로우)을 받았다
        val out = recommend(profile(goals = listOf("weight_loss", "glutes", "slim_legs"), equipment = setOf("DUMBBELL", "BODYWEIGHT")))
        assertNotEquals("diet_circuit", out.best.template.code)
        assertEquals("beginner", out.best.template.targetExperience)
        assertTrue(out.adapted.flatMap { it.exercises }.all { it.exercise.equipment?.name in setOf("DUMBBELL", "BODYWEIGHT", "OTHER", null) })
    }

    @Test
    fun `초보와 노비스는 중급 이상 템플릿을 받지 않는다`() {
        listOf("beginner", "novice").forEach { level ->
            val out = recommend(profile(experience = level, goals = listOf("bulk_up"), days = 5, minutes = 90))
            assertEquals(0, TemplateRecommendationEngine.rankOf(out.best.template.targetExperience), "$level -> ${out.best.template.code}")
            assertTrue(out.ranked.all { TemplateRecommendationEngine.rankOf(it.template.targetExperience) == 0 })
        }
    }

    @Test
    fun `맨몸뿐인 사용자는 맨몸 템플릿을, 헬스장 사용자는 기구 템플릿을 받는다`() {
        assertEquals("home_bodyweight_beginner", recommend(profile(equipment = setOf("BODYWEIGHT"))).best.template.code)
        val gym = recommend(profile(experience = "intermediate", goals = listOf("bulk_up"), days = 4, minutes = 60)).best.template
        assertTrue(gym.days.flatMap { it.exercises }.any { it.exercise.equipment?.name in setOf("BARBELL", "MACHINE", "CABLE") })
    }

    @Test
    fun `주 운동일수에 맞는 템플릿을 고른다`() {
        val five = recommend(profile(experience = "advanced", goals = listOf("bulk_up"), days = 5, minutes = 90)).best.template
        assertEquals(5, five.totalDays)
        val two = recommend(profile(days = 2, minutes = 45)).best.template
        assertTrue(kotlin.math.abs(two.totalDays - 2) <= 1)
    }

    @Test
    fun `하체·둔근 집중 목표는 하체 비중이 높은 템플릿으로 간다`() {
        val out = recommend(profile(goals = listOf("thick_legs", "glutes"), days = 3, minutes = 50))
        assertTrue(out.best.template.code in setOf("glute_leg_focus", "glute_leg_home"), out.best.template.code)
        val legs = out.adapted.sumOf { d -> d.exercises.count { FocusArea.LEGS.hits(it.exercise) } }
        assertTrue(legs >= 6, "하체 운동이 주 $legs 개")
    }

    @Test
    fun `무릎 부상이면 추천 플랜에 무릎을 쓰는 운동이 없다`() {
        val p = profile(goals = listOf("thick_legs", "glutes"), injuries = setOf("knee"))
        val service = RecoFitEvalTest.injuryService()
        val out = recommend(p)
        assertTrue(out.adapted.flatMap { it.exercises }.none { service.isRestrictedByRule(it.exercise, p.injuries) })
        // 둔근 집중은 무릎에 부담이 적은 브릿지·힙쓰러스트로 이어진다
        assertTrue(out.adapted.sumOf { d -> d.exercises.count { FocusArea.GLUTES.hits(it.exercise) } } >= 3)
    }

    @Test
    fun `대안은 최고 템플릿과 겹치지 않고 수준을 넘지 않는다`() {
        val out = recommend(profile())
        val alternatives = out.ranked.drop(1).take(3)
        assertFalse(alternatives.any { it.template.code == out.best.template.code })
        assertTrue(alternatives.all { TemplateRecommendationEngine.rankOf(it.template.targetExperience) == 0 })
    }

    @Test
    fun `추천 이유는 세 언어 모두 비어 있지 않고 반영한 사실만 말한다`() {
        val p = profile(goals = listOf("weight_loss", "glutes"), equipment = setOf("DUMBBELL", "BODYWEIGHT"), injuries = setOf("knee"))
        val out = recommend(p)
        listOf("ko", "en", "ja").forEach { locale ->
            val reason = TemplateRecoReason.compose(out, p, locale)
            assertTrue(reason.isNotBlank())
            assertFalse(reason.contains("template.reco"), "번역 키가 그대로 노출됐다: $reason")
        }
        assertTrue(TemplateRecoReason.compose(out, p, "ko").contains("무릎"))
        assertTrue(TemplateRecoReason.compose(out, p, "ja").contains("膝"))
        // 부상이 없으면 부상 문장이 없다
        val noInjury = profile(goals = listOf("weight_loss"))
        assertFalse(TemplateRecoReason.compose(recommend(noInjury), noInjury, "ko").contains("부상"))
    }
}
