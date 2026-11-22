package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.service.ChatHarness
import com.richjun.liftupai.domain.workout.service.FocusArea
import com.richjun.liftupai.domain.workout.service.RecoTestKit
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 오늘 추천의 집중 부위 몫(11-22) — 실측(complex_r4 심사, 모든 모델): "글루트 집중 → 글루트 1종목, 팔 집중 → 컬 없음".
 * 예전 규칙은 '최소 하나'였다. 이제 오늘 목표가 허락하면 종목 수의 약 35%(4종목 1, 5~7종목 2, 8종목 3).
 */
class RecoFocusShareTest {
    private val svc = ChatHarness().chat

    @Test
    fun `몫 표 — 종목 수·부위 수·오늘 목표`() {
        assertEquals(1, svc.focusShare(listOf(FocusArea.ARMS), null, 4).quota)
        assertEquals(2, svc.focusShare(listOf(FocusArea.ARMS), null, 6).quota)
        assertEquals(3, svc.focusShare(listOf(FocusArea.GLUTES), "full_body", 8).quota)
        assertEquals(2, svc.focusShare(listOf(FocusArea.GLUTES, FocusArea.LEGS), "legs", 4).quota, "부위가 둘이면 둘 이상")
        val chestDay = svc.focusShare(listOf(FocusArea.GLUTES), "chest", 6)
        assertEquals(1, chestDay.quota, "가슴 날의 글루트 집중은 예전처럼 하나")
        assertEquals(2, svc.focusShare(listOf(FocusArea.ARMS), "push", 6).quota, "밀기 날은 팔(삼두)을 허락")
        assertEquals(0, svc.focusShare(emptyList(), null, 6).quota)
    }

    @Test
    fun `몫만큼 후보에서 채운다 — 다른 동작 패턴, 밀기 날 팔은 삼두`() {
        val pool = RecoTestKit.catalog.filter { it.isPlanEligible }
        val chosen = listOf("barbell-full-squat", "barbell-bench-press", "barbell-bent-over-row-pronated-grip", "seated-shoulder-press-barbell")
            .map { RecoTestKit.bySlug.getValue(it) }.associateBy { it.id }
        val share = svc.focusShare(listOf(FocusArea.ARMS), null, 6)
        val picks = svc.ensureFocusPicks(chosen, pool, share, RecoTestKit.GYM.toSet(), { true })
        assertEquals(2, picks.size, picks.map { it.slug }.toString())
        assertTrue(picks.all { it.category == ExerciseCategory.ARMS })
        assertEquals(2, picks.map { it.movementPattern }.distinct().size, "컬 두 개가 아니라 다른 동작: ${picks.map { it.slug }}")

        val push = svc.ensureFocusPicks(chosen, pool, svc.focusShare(listOf(FocusArea.ARMS), "push", 6), RecoTestKit.GYM.toSet(), { true }, "push")
        assertTrue(push.isNotEmpty() && push.all { MuscleGroup.TRICEPS in it.muscleGroups }, "밀기 날 팔은 삼두: ${push.map { it.slug }}")

        // 양성 대조: 이미 몫을 채웠으면 더 넣지 않는다
        val full = chosen + picks.associateBy { it.id }
        assertTrue(svc.ensureFocusPicks(full, pool, share, RecoTestKit.GYM.toSet(), { true }).isEmpty())
    }

    @Test
    fun `팔 집중 60분 — 모델이 팔을 하나도 안 골라도 결과엔 팔 2종목, 프롬프트엔 숫자로`() {
        val h = ChatHarness(focusGoals = listOf("big_arms"), savedMinutes = 60, picks = listOf(
            "barbell-full-squat", "barbell-bench-press", "barbell-bent-over-row-pronated-grip", "seated-shoulder-press-barbell",
        ))
        val r = h.chat.getAIWorkoutRecommendation(1L)
        val arms = r.recommendation.exercises.count { RecoTestKit.byId[it.exerciseId.toLong()]!!.category == ExerciseCategory.ARMS }
        assertTrue(arms >= 2, "팔 집중 60분인데 팔 $arms 종목: ${r.recommendation.exercises.map { it.name }}")
        val prompt = slot<String>()
        verify { h.geminiMock.generateRecommendations(capture(prompt), any()) }
        assertTrue(prompt.captured.contains("at least 2 of the exercises must directly train arms"), prompt.captured.substringAfter("Hard rules").take(1200))
    }

    @Test
    fun `첫 세션(4종목)도 팔·글루트 집중이면 팔 1·글루트 1 — 운영 스모크 모양(모델이 스쿼트·로우·벤치·플랭크)`() {
        val h = ChatHarness(focusGoals = listOf("big_arms", "glutes"), completedSessions = 0L, equipment = listOf("DUMBBELL", "RESISTANCE_BAND", "BODYWEIGHT"),
            // 운영 스모크에서 모델이 고른 모양: 글루트 종목 둘(스쿼트·브리지)로 몫이 차서 팔이 0이었다
            picks = listOf("dumbbell-goblet-squat", RecoTestKit.catalog.first { it.isPlanEligible && it.movementPattern == "GLUTE_FOCUSED" && it.equipment?.name == "BODYWEIGHT" }.slug,
                "dumbbell-bench-press", "high-plank"))
        val r = h.chat.getAIWorkoutRecommendation(1L)
        val ex = r.recommendation.exercises.map { RecoTestKit.byId[it.exerciseId.toLong()]!! }
        assertTrue(ex.count { it.category == ExerciseCategory.ARMS } >= 1, "팔 0: ${ex.map { it.slug }}")
        assertTrue(ex.count { MuscleGroup.GLUTES in it.muscleGroups } >= 1, "글루트 0: ${ex.map { it.slug }}")
    }

    @Test
    fun `자를 때 부위마다 하나는 남긴다 — 정렬대로 자르면 팔 고립 종목이 늘 잘렸다`() {
        fun d(slug: String) = RecoTestKit.bySlug.getValue(slug).let { com.richjun.liftupai.domain.ai.dto.AIExerciseDetail(exerciseId = it.id.toString(), name = it.name, sets = 3, reps = "8-12", rest = 90, order = 0) }
        val sorted = listOf("dumbbell-goblet-squat", "dumbbell-bench-press", "dumbbell-bent-over-row", "high-plank", "dumbbell-curls").map { d(it) }
        val out = svc.takeKeepingFocus(sorted, 4, listOf(FocusArea.ARMS, FocusArea.GLUTES)) { RecoTestKit.byId[it.exerciseId.toLong()] }
        val slugs = out.map { RecoTestKit.byId[it.exerciseId.toLong()]!!.slug }
        assertTrue("dumbbell-curls" in slugs && "dumbbell-goblet-squat" in slugs && out.size == 4, slugs.toString())
        assertEquals((1..4).toList(), out.map { it.order })
        // 양성 대조: 부위가 다 들어 있으면 그냥 앞에서 자른다
        assertEquals(sorted.take(4).map { it.exerciseId }, svc.takeKeepingFocus(sorted, 4, listOf(FocusArea.GLUTES)) { RecoTestKit.byId[it.exerciseId.toLong()] }.map { it.exerciseId })
    }

    @Test
    fun `양성 대조 — 집중 부위가 없으면 예전과 같다(프롬프트 문구·종목)`() {
        val h = ChatHarness(savedMinutes = 60)
        val r = h.chat.getAIWorkoutRecommendation(1L)
        val prompt = slot<String>()
        verify { h.geminiMock.generateRecommendations(capture(prompt), any()) }
        assertTrue(prompt.captured.contains("No focus areas are listed"))
        assertTrue(r.recommendation.exercises.size >= 4)
    }
}
