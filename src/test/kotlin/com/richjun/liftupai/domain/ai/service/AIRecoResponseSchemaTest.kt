package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.workout.service.ChatHarness
import com.richjun.liftupai.domain.workout.service.RecoTestKit
import io.mockk.every
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * AI 운동 추천 응답 스키마(2026-11-05): 모델에게 coaching_message·workout_focus·progression_note 를 따로 쓰게 하지 않는다.
 * 같은 내용이 ai_insights(이유·핵심·다음 단계)에 있어 채팅 기록·운동 화면에 두 번씩 나갔고, 모델 시간은 출력 길이에 비례했다
 * (gemini-3.8-flash 실측 출력 약 760 → 530토큰, 8초 → 6초).
 */
class AIRecoResponseSchemaTest {

    @Test
    @DisplayName("프롬프트 스키마에 중복 산문 필드가 없고 ai_insights 세 필드와 짧은 팁 2개를 요구한다")
    fun promptAsksOnlyInsights() {
        val h = ChatHarness(completedSessions = 0L)
        h.chat.getAIWorkoutRecommendation(1L)
        val prompt = slot<String>()
        verify { h.geminiMock.generateRecommendations(capture(prompt)) }
        val p = prompt.captured
        listOf("coaching_message", "workout_focus", "progression_note").forEach {
            assertFalse(p.contains("\"$it\""), "프롬프트에 $it 가 남아 있다")
        }
        listOf("workout_rationale", "key_point", "next_step").forEach {
            assertTrue(p.contains("\"$it\""), "프롬프트에 $it 가 없다")
        }
        assertTrue(p.contains("2 short actionable tips"))
    }

    @Test
    @DisplayName("새 형식 응답: 인사이트는 ai_insights 에서, 옛 산문 필드는 비어 있다(앱·채팅 기록에 같은 설명이 두 번 나가지 않음)")
    fun parsesInsightsWithoutProseDuplicates() {
        val h = ChatHarness(completedSessions = 3L)
        val ids = listOf("barbell-full-squat", "barbell-bench-press", "cable-pulldown", "dumbbell-curls")
            .map { RecoTestKit.bySlug.getValue(it).id }
        every { h.geminiMock.generateRecommendations(any()) } returns """{"workout_name":"전신 기초","difficulty":"beginner",
            "estimated_calories":220,"target_muscles":[],"equipment":[],
            "exercises":[${ids.mapIndexed { i, id -> """{"id":$id,"sets":3,"reps":"10-12","rest_seconds":90,"order":${i + 1}}""" }.joinToString(",")}],
            "tips":["팁 하나","팁 둘"],
            "ai_insights":{"workout_rationale":"이유","key_point":"핵심","next_step":"다음"}}"""
        val r = h.chat.getAIWorkoutRecommendation(1L)
        assertEquals("이유", r.aiInsights?.workoutRationale)
        assertEquals("핵심", r.aiInsights?.keyPoint)
        assertEquals("다음", r.aiInsights?.nextStep)
        assertNull(r.recommendation.coachingMessage)
        assertNull(r.recommendation.workoutFocus)
        assertNull(r.recommendation.progressionNote)
        assertEquals(listOf("팁 하나", "팁 둘"), r.recommendation.tips)
        assertTrue(r.recommendation.exercises.size >= 4)
    }
}
