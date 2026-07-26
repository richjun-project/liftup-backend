package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.RevenueCatEvent
import com.richjun.liftupai.domain.subscription.dto.RevenueCatWebhookEvent
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * RevenueCatWebhookService 크리티컬 경로 테스트
 *
 * 커버리지:
 *  - 동일 이벤트 재전송 시 구독 레코드가 중복 생성되지 않는가 (멱등성)
 *  - 알 수 없는 이벤트 타입이 예외 없이 처리되는가
 *  - Authorization 검증(웹훅 "서명" 메커니즘)이 올바르게 거부/허용하는가
 *
 * 참고: 이 서비스의 웹훅 인증은 HMAC 서명 검증이 아니라
 * `Authorization: Bearer {revenuecat.webhook-secret}` 고정 비밀값 비교 방식이다
 * (validateAuthorization 참조). 실제 RevenueCat 서명 헤더 검증은 존재하지 않는다.
 */
class RevenueCatWebhookServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val paymentHistoryRepository = mockk<PaymentHistoryRepository>(relaxed = true)

    private fun buildService(secret: String = "test-secret") = RevenueCatWebhookService(
        userRepository = userRepository,
        subscriptionRepository = subscriptionRepository,
        paymentHistoryRepository = paymentHistoryRepository,
        webhookSecret = secret
    )

    private val user = User(id = 1L, email = "test@example.com", nickname = "tester")

    @Test
    fun `동일 구매 이벤트를 두 번 처리해도 구독 레코드는 하나만 생성된다`() {
        val service = buildService()
        var storedSubscription: Subscription? = null
        val savedSubscriptions = mutableListOf<Subscription>()

        every { userRepository.findById(1L) } returns Optional.of(user)
        every { subscriptionRepository.findByUser(user) } answers { Optional.ofNullable(storedSubscription) }
        every { subscriptionRepository.save(any()) } answers {
            val sub = firstArg<Subscription>()
            storedSubscription = sub
            savedSubscriptions.add(sub)
            sub
        }

        val event = RevenueCatEvent(
            type = "INITIAL_PURCHASE",
            appUserId = "1",
            productId = "pro_monthly",
            periodType = "NORMAL",
            purchasedAtMs = 1_700_000_000_000,
            expirationAtMs = 1_800_000_000_000,
            transactionId = "txn-123"
        )
        val webhookEvent = RevenueCatWebhookEvent(event = event)

        // 동일 webhook 이벤트가 두 번 전달됨 (재전송 시나리오)
        service.handleWebhookEvent(webhookEvent)
        service.handleWebhookEvent(webhookEvent)

        val distinctSubscriptionRecords = savedSubscriptions.map { System.identityHashCode(it) }.distinct()
        assertEquals(
            1, distinctSubscriptionRecords.size,
            "재전송된 이벤트가 서로 다른 Subscription 레코드를 생성함 — 멱등성 위반"
        )
        assertEquals(SubscriptionPlan.PRO, storedSubscription!!.plan)
        assertEquals(SubscriptionStatus.ACTIVE, storedSubscription!!.status)
    }

    @Test
    fun `알 수 없는 이벤트 타입은 예외 없이 무시된다`() {
        val service = buildService()
        val event = RevenueCatEvent(type = "SOME_FUTURE_EVENT_TYPE", appUserId = "1")
        val webhookEvent = RevenueCatWebhookEvent(event = event)

        assertDoesNotThrow { service.handleWebhookEvent(webhookEvent) }

        // 알 수 없는 타입은 어떤 사용자/구독 조회도 트리거하지 않아야 한다
        io.mockk.verify(exactly = 0) { userRepository.findById(any()) }
    }

    @Test
    fun `event가 null인 webhook payload도 예외 없이 무시된다`() {
        val service = buildService()
        val webhookEvent = RevenueCatWebhookEvent(event = null)

        assertDoesNotThrow { service.handleWebhookEvent(webhookEvent) }
    }

    @Test
    fun `Authorization 헤더가 없거나 틀리면 거부되고, 올바른 secret만 통과한다`() {
        val service = buildService(secret = "correct-secret")

        assertFalse(service.validateAuthorization(null), "Authorization 헤더 없음은 거부되어야 함")
        assertFalse(service.validateAuthorization("Bearer wrong-secret"), "잘못된 secret은 거부되어야 함")
        assertFalse(service.validateAuthorization("correct-secret"), "Bearer 접두사 없는 값은 거부되어야 함")
        assertTrue(service.validateAuthorization("Bearer correct-secret"), "올바른 secret은 허용되어야 함")
    }

    @Test
    fun `webhook secret이 설정되지 않으면 어떤 요청도 인증되지 않는다 (fail-closed)`() {
        val service = buildService(secret = "")

        assertFalse(
            service.validateAuthorization("Bearer anything"),
            "webhook secret 미설정 시 요청을 거부(fail-closed)해야 함"
        )
        assertFalse(service.validateAuthorization(null))
    }
}
