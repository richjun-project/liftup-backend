package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.dto.request.GenerateAIPlanRequest
import com.richjun.liftupai.domain.workout.service.AIPlanGenerationService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.util.AopTestUtils
import org.springframework.test.util.ReflectionTestUtils

/**
 * 실제 서비스 경로가 구독 여부를 Gemini 호출까지 넘기는지 — 구독 중(ACTIVE·유료 플랜)만 pro=true.
 * 만료·취소·무료 플랜은 무료로 본다.
 */
class PaidModelRoutingTest : ApiTestSupport() {
    @Autowired private lateinit var planService: AIPlanGenerationService
    @Autowired private lateinit var analysisService: AIAnalysisService
    @Autowired private lateinit var subscriptionRepository: SubscriptionRepository

    private fun subscribe(user: User, plan: SubscriptionPlan, status: SubscriptionStatus) =
        subscriptionRepository.save(Subscription(user = user, plan = plan, status = status))

    private fun <T> withGemini(target: Any, spy: GeminiAIService, block: () -> T): T {
        val original = ReflectionTestUtils.getField(target, "geminiAIService")
        ReflectionTestUtils.setField(target, "geminiAIService", spy)
        try { return block() } finally { ReflectionTestUtils.setField(target, "geminiAIService", original) }
    }

    private fun planProFlag(user: User): Boolean? {
        var seen: Boolean? = null
        val spy = mockk<GeminiAIService>(relaxed = true)
        every { spy.generatePlanContent(any(), any()) } answers { seen = secondArg(); throw IllegalStateException("captured") }
        every { spy.generatePlanContentStreaming(any(), any(), any()) } answers { seen = thirdArg(); throw IllegalStateException("captured") }
        withGemini(AopTestUtils.getUltimateTargetObject<AIPlanGenerationService>(planService), spy) {
            runCatching {
                planService.generateAndApplyPlan(user, GenerateAIPlanRequest(experienceLevel = "beginner", goals = listOf("general_fitness"), gender = "female", age = 30, weeklyDays = 3, sessionDuration = 45, equipment = listOf("BODYWEIGHT"), locale = "ko"))
            }
        }
        return seen
    }

    private fun recoProFlag(user: User): Boolean? {
        var seen: Boolean? = null
        val spy = mockk<GeminiAIService>(relaxed = true)
        every { spy.generateRecommendations(any(), any()) } answers { seen = secondArg(); "{}" }
        withGemini(AopTestUtils.getUltimateTargetObject<AIAnalysisService>(analysisService), spy) {
            runCatching { analysisService.getAIWorkoutRecommendation(user.id, localeOverride = "ko") }
        }
        return seen
    }

    @Test
    fun `구독 중인 사용자만 유료 모델 경로`() {
        allowedErrorLoggers += listOf(AIPlanGenerationService::class.java.name, AIAnalysisService::class.java.name, GeminiAIService::class.java.name)
        val paid = newUser("paid").also { subscribe(it, SubscriptionPlan.PRO, SubscriptionStatus.ACTIVE) }
        val free = newUser("free")
        val expired = newUser("expired").also { subscribe(it, SubscriptionPlan.PRO, SubscriptionStatus.EXPIRED) }
        val freePlanRow = newUser("freerow").also { subscribe(it, SubscriptionPlan.FREE, SubscriptionStatus.ACTIVE) }
        assertEquals(true, planProFlag(paid), "구독 중 → 플랜 pro")
        assertEquals(false, planProFlag(free), "구독 없음 → 플랜 무료")
        assertEquals(false, planProFlag(expired), "만료 → 플랜 무료")
        assertEquals(false, planProFlag(freePlanRow), "무료 플랜 행 → 플랜 무료")
        assertEquals(true, recoProFlag(paid), "구독 중 → 오늘 추천 pro")
        assertEquals(false, recoProFlag(free), "구독 없음 → 오늘 추천 무료")
        assertEquals(false, recoProFlag(expired), "만료 → 오늘 추천 무료")
    }
}
