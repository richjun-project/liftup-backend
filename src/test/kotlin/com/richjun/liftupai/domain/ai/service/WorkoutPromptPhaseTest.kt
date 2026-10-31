package com.richjun.liftupai.domain.ai.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

/**
 * AI 추천 프롬프트의 주기화 단계(2026-10-31). 운영: 기록 0회에서 AI 추천을 받은 4명 전원이 "디로드" 제목을 받았다
 * (기록이 없으면 마지막 운동 99일 전으로 계산 → 14일 이상 = DELOAD).
 */
class WorkoutPromptPhaseTest {

    private val now = LocalDateTime.of(2026, 10, 31, 3, 0)
    private fun daysAgo(vararg days: Long) = days.map { now.minusDays(it) }

    @Test
    @DisplayName("기록이 없는 신규 사용자: DELOAD 가 아니라 첫 세션")
    fun newUserIsFirstSession() {
        val phase = WorkoutPromptPhase.resolve(emptyList(), now)
        assertEquals(WorkoutPromptPhase.FIRST_SESSION, phase)
        assertFalse(WorkoutPromptPhase.guide(phase).contains("Current phase is DELOAD"))
        assertTrue(WorkoutPromptPhase.guide(phase).contains("not a deload"))
    }

    @Test
    @DisplayName("2주 넘게 쉬었다 돌아온 사용자: 복귀 세션(회복 주간 아님)")
    fun longBreakIsReturn() {
        assertEquals(WorkoutPromptPhase.RETURN, WorkoutPromptPhase.resolve(daysAgo(20, 22, 25), now))
        assertEquals(WorkoutPromptPhase.RETURN, WorkoutPromptPhase.resolve(daysAgo(14), now))
    }

    @Test
    @DisplayName("이번 주에 시작한 사용자: ACCUMULATION")
    fun recentStarterAccumulates() {
        assertEquals(WorkoutPromptPhase.ACCUMULATION, WorkoutPromptPhase.resolve(daysAgo(1, 3), now))
    }

    @Test
    @DisplayName("4주차라도 최근 28일 세션이 적으면 디로드하지 않는다")
    fun sparseTrainingSkipsDeload() {
        assertEquals(WorkoutPromptPhase.ACCUMULATION, WorkoutPromptPhase.resolve(daysAgo(2, 10, 22), now))
    }

    @Test
    @DisplayName("4주차이고 꾸준히 했으면 DELOAD 는 그대로")
    fun consistentTrainingKeepsDeload() {
        assertEquals(WorkoutPromptPhase.DELOAD, WorkoutPromptPhase.resolve(daysAgo(1, 4, 7, 11, 15, 18, 22), now))
        assertEquals(WorkoutPromptPhase.INTENSIFICATION, WorkoutPromptPhase.resolve(daysAgo(1, 4, 8), now))
        assertEquals(WorkoutPromptPhase.REALIZATION, WorkoutPromptPhase.resolve(daysAgo(1, 4, 8, 12, 15), now))
    }
}
