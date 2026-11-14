package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.workout.entity.Exercise
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 음성 대조(2026-11-14): RecoFitEvalTest 의 플랜 불변 조건이 "실제로 실패할 수 있음"을 보인다.
 * 304 가 받은 초보 맨몸 템플릿을 균형 단계 없이 그대로 두면 잡히고, 같은 템플릿을 어댑터로 적용하면 통과해야 한다.
 */
class RecoFitBodyweightControlTest {
    private val exercises = RecoFitEvalTest.loadExercises()
    private val bySlug = exercises.associateBy { it.slug }
    private fun ex(slug: String): Exercise = bySlug[slug] ?: error("catalog has no $slug")
    private fun codes(v: List<PlanInvariants.Violation>) = v.map { it.code }.toSet()

    private val beginnerTemplate = PresetTemplates.ALL.first { it.code == "home_bodyweight_beginner" }

    @Test
    @DisplayName("초보 맨몸 템플릿 원본(3일 내내 푸시업, 등 운동 0)은 등 위쪽·밀기 비율 위반으로 잡힌다")
    fun rawBeginnerTemplateViolates() {
        val days = beginnerTemplate.days.map { d -> PlanInvariants.Day(d.dayNumber, d.workoutType.name, d.exercises.map { ex(it.slug) }) }
        val v = PlanInvariants.check(days, setOf("BODYWEIGHT"))
        assertTrue("UPPER_BACK" in codes(v), "등 위쪽 없는 전신 날을 못 잡았다: $v")
        assertTrue("PUSH_BACK_RATIO" in codes(v), "밀기 과다를 못 잡았다: $v")
    }

    @Test
    @DisplayName("같은 템플릿을 기구 없는 사람에게 어댑터로 적용하면 불변 조건을 모두 지킨다(양성 대조)")
    fun adaptedBeginnerTemplatePasses() {
        val engine = RecoFitEvalTest.buildEngine(exercises, emptySet())
        val profile = TemplateRecommendationEngine.RecoProfile(
            experience = "beginner", goals = listOf("general_fitness"), weeklyDays = 3, sessionDuration = 30,
            equipment = setOf("BODYWEIGHT"), injuries = emptySet(), focus = emptyList(), age = 39,
        )
        val reco = engine.recommend(profile).ranked.first { it.template.code == "home_bodyweight_beginner" }.template
        val adapted = engine.adaptWithFocus(reco, profile)
        val days = adapted.map { d -> PlanInvariants.Day(d.dayNumber, d.workoutType, d.exercises.map { it.exercise }) }
        val v = PlanInvariants.check(days, setOf("BODYWEIGHT"))
        assertTrue(v.isEmpty(), "적용된 초보 맨몸 플랜이 불변 조건을 어겼다: $v\n${adapted.map { d -> d.exercises.map { it.exercise.slug } }}")
        assertTrue(adapted.all { d -> d.exercises.any { PlanInvariants.isUpperBack(it.exercise) } }, "날마다 등 위쪽 운동이 있어야 한다")
    }

    @Test
    @DisplayName("필요 도구: 기구 없는 사람의 인버티드 로우·턱걸이는 위반, 헬스장 사용자에게는 통과")
    fun requiresViolation() {
        val day = PlanInvariants.Day(1, "PULL", listOf(ex("inverted-row"), ex("chin-up"), ex("superman-pulls")))
        val none = PlanInvariants.check(listOf(day), setOf("BODYWEIGHT"))
        assertEquals(2, none.count { it.code == "REQUIRES" }, "$none")
        val gym = PlanInvariants.check(listOf(day), setOf("BARBELL", "CABLE", "MACHINE", "BODYWEIGHT"))
        assertTrue(gym.none { it.code == "REQUIRES" }, "$gym")
    }

    @Test
    @DisplayName("진짜 당기기: 덤벨이 있는데 상체 날이 Y 레이즈뿐이면 위반, 덤벨 로우가 있으면 통과, 기구 없으면 요구하지 않는다")
    fun realPullOnUpperDays() {
        val row = exercises.first { it.isPlanEligible && it.movementPattern == "DUMBBELL_ROW" }
        val yOnly = PlanInvariants.Day(1, "UPPER", listOf(ex("push-ups-bodyweight"), ex("standing-y-raise")))
        assertTrue("REAL_PULL" in codes(PlanInvariants.check(listOf(yOnly), setOf("DUMBBELL", "BODYWEIGHT"))))
        val withRow = yOnly.copy(exercises = yOnly.exercises + row)
        assertTrue("REAL_PULL" !in codes(PlanInvariants.check(listOf(withRow), setOf("DUMBBELL", "BODYWEIGHT"))))
        assertTrue("REAL_PULL" !in codes(PlanInvariants.check(listOf(yOnly), setOf("BODYWEIGHT"))))
    }
}
