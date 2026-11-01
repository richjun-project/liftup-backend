package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

class StoreChallengeTest {
    private val user = User(id = 7L, email = "a@example.com", nickname = "준")
    private val seoul = ZoneId.of("Asia/Seoul")
    private val repo = mockk<WorkoutSessionRepository>()

    private fun service(day: LocalDate) = StoreChallengeService(repo).also { it.today = { day } }

    private fun session(status: SessionStatus) =
        WorkoutSession(user = user, startTime = LocalDateTime.of(2026, 11, 4, 1, 0), status = status)

    @Test
    fun `기간 밖이면 프롬프트에 넣지 않는다`() {
        assertNull(service(LocalDate.of(2026, 11, 2)).section(user, seoul))
        assertNull(service(LocalDate.of(2026, 12, 1)).section(user, seoul))
    }

    @Test
    fun `기간 안이면 완료 운동만 세고 남은 날을 오늘 포함으로 알려 준다`() {
        val since = slot<LocalDateTime>()
        every { repo.findByUserAndStartTimeAfter(user, capture(since)) } returns listOf(
            session(SessionStatus.COMPLETED), session(SessionStatus.COMPLETED), session(SessionStatus.COMPLETED),
            session(SessionStatus.IN_PROGRESS), session(SessionStatus.CANCELLED),
        )
        val text = service(LocalDate.of(2026, 11, 10)).section(user, seoul)!!
        assertTrue(text.contains("3/12 finished workouts"), text)
        assertTrue(text.contains("21 days left including today"), text)
        assertTrue(text.contains("never promise any"))
        // 11-03 0시 KST = 11-02 15시 UTC 부터 센다
        assertEquals(LocalDateTime.of(2026, 11, 2, 15, 0), since.captured)
    }

    @Test
    fun `마지막 날은 1일 남음`() {
        assertTrue(StoreChallenge.section(StoreChallenge.END, 12).contains("12/12 finished workouts so far, 1 days left"))
    }

    @Test
    fun `에이전트 구역 끝에 붙고, 없으면 그대로`() {
        val agent = CoachAgentService(mockk<GeminiAIService>(relaxed = true), mockk(), CoachToolbox(emptyList()), mockk(), ObjectMapper())
        val plain = agent.agentSection("준", emptyList())
        val withChallenge = agent.agentSection("준", emptyList(), StoreChallenge.section(LocalDate.of(2026, 11, 10), 0))
        assertTrue(!plain.contains("LIFTUP CHALLENGE"))
        assertTrue(withChallenge.startsWith(plain))
        assertTrue(withChallenge.trimEnd().endsWith("never promise any."))
    }
}
