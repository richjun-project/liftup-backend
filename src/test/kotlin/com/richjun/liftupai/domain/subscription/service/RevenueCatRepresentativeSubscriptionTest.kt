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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * 스토어 구독과 promotional 평생 이용권이 겹칠 때 "더 긴 쪽이 대표 구독" 규칙.
 *
 * 실제 사례(user 36): Play 월간 결제 2분 뒤 평생 Pro 를 수동 부여 → 월간은 해지로 만료.
 * 이때 DB 에 출처가 없고 autoRenew 가 true 로 남아 유료/프로모션 구분이 불가능했다.
 */
class RevenueCatRepresentativeSubscriptionTest {

    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val paymentHistoryRepository = mockk<PaymentHistoryRepository>(relaxed = true)
    private val user = User(id = 36L, email = "t@example.com", nickname = "t")
    private var stored: Subscription? = null

    private val service = RevenueCatWebhookService(
        userRepository = userRepository,
        subscriptionRepository = subscriptionRepository,
        paymentHistoryRepository = paymentHistoryRepository,
        webhookSecret = "s",
    ).also {
        every { userRepository.findById(36L) } returns Optional.of(user)
        every { subscriptionRepository.findByUser(user) } answers { Optional.ofNullable(stored) }
        every { subscriptionRepository.findByUser_Id(36L) } answers { Optional.ofNullable(stored) }
        every { subscriptionRepository.save(any()) } answers { firstArg<Subscription>().also { stored = it } }
    }

    private val DAY = 86_400_000L
    private val T0 = 1_785_652_112_426L // 2026-08-02

    private fun send(
        type: String, store: String, expiration: Long?, periodType: String = "NORMAL",
        tx: String = "$type-$store-$expiration", cancelReason: String? = null,
    ) = service.handleWebhookEvent(
        RevenueCatWebhookEvent(
            event = RevenueCatEvent(
                type = type, appUserId = "36", productId = "p", periodType = periodType,
                purchasedAtMs = T0, expirationAtMs = expiration, store = store,
                transactionId = tx, cancelReason = cancelReason,
            )
        )
    )

    private fun paid(expiration: Long = T0 + 31 * DAY) = send("INITIAL_PURCHASE", "PLAY_STORE", expiration)
    private fun promoLifetime(expiration: Long? = null) =
        send("NON_RENEWING_PURCHASE", "PROMOTIONAL", expiration, periodType = "PROMOTIONAL")

    private fun sub() = stored!!

    @Test
    fun `유료 결제 후 평생 이용권 부여 - 출처는 PROMOTIONAL, 자동갱신 아님, 만료일 없음`() {
        paid()
        assertEquals("PLAY_STORE", sub().store)
        assertTrue(sub().autoRenew)

        promoLifetime()
        assertEquals("PROMOTIONAL", sub().store)
        assertFalse(sub().autoRenew, "프로모션 부여는 자동갱신이 아니다 — 예전엔 true 로 남았다")
        assertNull(sub().expiryDate)
        assertEquals(SubscriptionPlan.PRO, sub().plan)
    }

    @Test
    fun `프로모션 만료일이 먼 미래로 오는 경우도 같은 결과`() {
        paid()
        promoLifetime(expiration = T0 + 200L * 365 * DAY)
        assertEquals("PROMOTIONAL", sub().store)
        assertFalse(sub().autoRenew)
    }

    @Test
    fun `평생 이용권 보유 중 월간 결제 - 대표 구독은 평생 이용권 그대로`() {
        promoLifetime()
        paid()
        assertEquals("PROMOTIONAL", sub().store, "짧은 구독이 출처를 덮어쓰면 안 된다")
        assertNull(sub().expiryDate, "평생 만료일이 월간 만료일로 줄어들면 안 된다")
        assertFalse(sub().autoRenew)
    }

    @Test
    fun `평생 이용권 보유자의 월간 구독 만료 - 권한 유지`() {
        paid()
        promoLifetime()
        send("EXPIRATION", "PLAY_STORE", T0 + 31 * DAY)
        assertEquals(SubscriptionStatus.ACTIVE, sub().status)
        assertEquals(SubscriptionPlan.PRO, sub().plan)
    }

    @Test
    fun `평생 이용권 보유자의 월간 구독 환불 - 평생 권한은 회수되지 않는다`() {
        paid()
        promoLifetime()
        send("CANCELLATION", "PLAY_STORE", T0 + 31 * DAY, cancelReason = "CUSTOMER_SUPPORT")
        assertEquals(SubscriptionStatus.ACTIVE, sub().status, "예전엔 월간 환불이 평생 이용권까지 즉시 회수했다")
        assertEquals(SubscriptionPlan.PRO, sub().plan)
        assertNull(sub().expiryDate)
    }

    @Test
    fun `평생 이용권 보유자의 월간 결제 문제 - PENDING 으로 강등되지 않는다`() {
        paid()
        promoLifetime()
        send("BILLING_ISSUE", "PLAY_STORE", T0 + 31 * DAY)
        assertEquals(SubscriptionStatus.ACTIVE, sub().status)
    }

    // ── 양성 대조: 가드가 정상 흐름까지 막지 않는다 ──

    @Test
    fun `유료 구독만 있을 때 환불 - 즉시 회수된다`() {
        paid()
        send("CANCELLATION", "PLAY_STORE", T0 + 31 * DAY, cancelReason = "CUSTOMER_SUPPORT")
        assertEquals(SubscriptionStatus.EXPIRED, sub().status)
        assertEquals(SubscriptionPlan.FREE, sub().plan)
    }

    @Test
    fun `유료 구독만 있을 때 만료 - FREE 로 강등된다`() {
        paid()
        send("EXPIRATION", "PLAY_STORE", T0 + 31 * DAY)
        assertEquals(SubscriptionStatus.EXPIRED, sub().status)
        assertEquals(SubscriptionPlan.FREE, sub().plan)
    }

    @Test
    fun `유료 구독만 있을 때 일반 해지 - 자동갱신만 꺼지고 권한은 만료일까지 유지`() {
        paid()
        send("CANCELLATION", "PLAY_STORE", T0 + 31 * DAY, cancelReason = "UNSUBSCRIBE")
        assertFalse(sub().autoRenew)
        assertEquals(SubscriptionStatus.ACTIVE, sub().status)
    }

    @Test
    fun `유료 구독만 있을 때 결제 문제 - PENDING`() {
        paid()
        send("BILLING_ISSUE", "PLAY_STORE", T0 + 31 * DAY)
        assertEquals(SubscriptionStatus.PENDING, sub().status)
    }

    @Test
    fun `만료 후 갱신 이벤트 - PRO 로 복구된다 (예전엔 status 만 ACTIVE, plan 은 FREE 로 남았다)`() {
        paid()
        send("EXPIRATION", "PLAY_STORE", T0 + 31 * DAY)
        send("RENEWAL", "PLAY_STORE", T0 + 62 * DAY)
        assertEquals(SubscriptionStatus.ACTIVE, sub().status)
        assertEquals(SubscriptionPlan.PRO, sub().plan)
        assertEquals("PLAY_STORE", sub().store)
    }

    @Test
    fun `월간이 연간으로 갱신되며 더 길어지면 만료일이 늘어난다`() {
        paid()
        send("RENEWAL", "PLAY_STORE", T0 + 365 * DAY)
        val expected = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(T0 + 365 * DAY), java.time.ZoneOffset.UTC)
        assertEquals(expected, sub().expiryDate)
    }
}
