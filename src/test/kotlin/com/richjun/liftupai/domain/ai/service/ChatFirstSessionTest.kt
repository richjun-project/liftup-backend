package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.service.ChatHarness
import com.richjun.liftupai.domain.workout.service.FirstSessionRule
import com.richjun.liftupai.domain.workout.util.SessionDurationFitter
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 채팅 "오늘의 운동 추천"(/api/chat/recommendations/workout)도 첫 세션 규칙(앞 4종목·30분 안)을 따른다.
 *
 * 2026-11-03 운영: 온보딩을 마친 초보(BEGINNER·SPARTAN·저장 60분)가 24초 뒤 채팅에서 이 버튼을 눌러 16초를 기다렸고,
 * 트레이너 첫 인사("4종목 약 30분, 첫날은 앞 3종목만")와 달리 6종목·60분·난이도 "중급"을 받은 뒤 시작 없이 떠났다.
 * 하루 상세·대시보드·트레이너 첫 인사·에이전트 도구는 10-31 부터 규칙을 따랐고 이 경로만 빠져 있었다.
 */
class ChatFirstSessionTest {

    private val sixPicks = listOf(
        "barbell-bench-press", "barbell-full-squat", "cable-pulldown",
        "seated-shoulder-press-barbell", "dumbbell-curls", "barbell-romanian-deadlift",
    )

    private fun minutes(r: com.richjun.liftupai.domain.ai.dto.AIWorkoutRecommendationResponse): Int = SessionDurationFitter.estimateMinutes(
        r.recommendation.exercises.map { SessionDurationFitter.Slot(it.sets, it.reps, it.rest, it.rest) }
    )

    @Test
    @DisplayName("완료 0회 + 저장 60분 + 시간 요청 없음 → 30분·4종목 이하, 추정 시간도 30분 근처")
    fun firstSessionIsShort() {
        val r = ChatHarness(level = ExperienceLevel.BEGINNER, savedMinutes = 60, completedSessions = 0L, picks = sixPicks)
            .chat.getAIWorkoutRecommendation(userId = 1L, duration = null)
        assertEquals(FirstSessionRule.MAX_MINUTES, r.recommendation.duration)
        assertTrue(r.recommendation.exercises.size in 3..FirstSessionRule.MAX_EXERCISES, "종목 ${r.recommendation.exercises.map { it.name }}")
        assertTrue(minutes(r) <= 33, "추정 ${minutes(r)}분 (30분 +5% 이내 기대)")
    }

    @Test
    @DisplayName("AI 가 6종목을 골라도 첫 세션은 앞(큰 복합)에서 4종목 이하, 순서 번호는 1..n")
    fun capsAiPicks() {
        val r = ChatHarness(savedMinutes = 60, completedSessions = 0L, picks = sixPicks).chat.getAIWorkoutRecommendation(1L)
        val ex = r.recommendation.exercises
        // 30분 맞춤(fitToDuration)이 하나를 더 뺄 수 있다(기존 30분 요청 테스트도 3~5종목) — 상한만 본다
        assertTrue(ex.size in 3..FirstSessionRule.MAX_EXERCISES, "종목 ${ex.map { it.name }}")
        assertEquals((1..ex.size).toList(), ex.map { it.order })
    }

    @Test
    @DisplayName("저장 시간이 30분보다 짧으면 그 값(20분)")
    fun shorterSavedWins() {
        val r = ChatHarness(savedMinutes = 20, completedSessions = 0L).chat.getAIWorkoutRecommendation(1L)
        assertEquals(20, r.recommendation.duration)
    }

    @Test
    @DisplayName("첫 세션이라도 사용자가 시간을 직접 고르면 그 시간(60분)")
    fun explicitDurationWins() {
        val r = ChatHarness(savedMinutes = 60, completedSessions = 0L, picks = sixPicks).chat.getAIWorkoutRecommendation(1L, duration = 60)
        assertEquals(60, r.recommendation.duration)
        assertTrue(r.recommendation.exercises.size >= 5, "60분 요청인데 ${r.recommendation.exercises.size}종목")
    }

    @Test
    @DisplayName("기록이 있으면(완료 3회) 저장된 60분 그대로 — 첫 세션 규칙은 한 번 끝내면 풀린다")
    fun afterFirstWorkoutUsesSaved() {
        val r = ChatHarness(savedMinutes = 60, completedSessions = 3L, picks = sixPicks).chat.getAIWorkoutRecommendation(1L)
        assertEquals(60, r.recommendation.duration)
        assertTrue(r.recommendation.exercises.size >= 5)
    }

    @Test
    @DisplayName("표시 난이도와 프롬프트 난이도는 사용자 수준: 초보 → 초급·beginner (AI 가 intermediate 라고 해도)")
    fun difficultyFollowsLevel() {
        val h = ChatHarness(level = ExperienceLevel.BEGINNER, completedSessions = 0L)
        val r = h.chat.getAIWorkoutRecommendation(1L)
        assertEquals("초급", r.recommendation.difficulty)
        verify { h.geminiMock.generateRecommendations(match { it.contains("Difficulty code: beginner") }) }
    }

    @Test
    @DisplayName("요청 난이도가 있으면 그것이 우선, 중급 사용자는 중급")
    fun requestedDifficultyWins() {
        val adv = ChatHarness(level = ExperienceLevel.BEGINNER).chat.getAIWorkoutRecommendation(1L, difficulty = "advanced")
        assertEquals("고급", adv.recommendation.difficulty)
        val mid = ChatHarness(level = ExperienceLevel.INTERMEDIATE).chat.getAIWorkoutRecommendation(1L)
        assertEquals("중급", mid.recommendation.difficulty)
    }
}
