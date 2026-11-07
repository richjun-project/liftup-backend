package com.richjun.liftupai.domain.subscription.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.richjun.liftupai.domain.analytics.AppEvent
import com.richjun.liftupai.domain.analytics.AppEventRepository
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.RevenueCatAttribute
import com.richjun.liftupai.domain.subscription.dto.RevenueCatEvent
import com.richjun.liftupai.domain.subscription.dto.RevenueCatWebhookEvent
import com.richjun.liftupai.domain.subscription.entity.Subscription
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Optional

/**
 * 광고 귀속(2026-11-07): RevenueCat 웹훅의 subscriber_attributes 에 실린 Apple Search Ads 귀속을
 * app_events(store_ad_attribution)에 남기는지. 귀속 기록이 구독 처리를 막지 않는지.
 */
class RevenueCatAdAttributionTest {

    private val userRepository = mockk<UserRepository>()
    private val subscriptionRepository = mockk<SubscriptionRepository>()
    private val paymentHistoryRepository = mockk<PaymentHistoryRepository>(relaxed = true)
    private val appEventRepository = mockk<AppEventRepository>(relaxed = true)
    private val user = User(id = 301L, email = "ad@example.com", nickname = "ad")
    private var stored: Subscription? = null

    private val service = RevenueCatWebhookService(
        userRepository = userRepository,
        subscriptionRepository = subscriptionRepository,
        paymentHistoryRepository = paymentHistoryRepository,
        appEventRepository = appEventRepository,
        webhookSecret = "s",
    ).also {
        every { userRepository.findById(301L) } returns Optional.of(user)
        every { subscriptionRepository.findByUser(user) } answers { Optional.ofNullable(stored) }
        every { subscriptionRepository.findByUser_Id(301L) } answers { Optional.ofNullable(stored) }
        every { subscriptionRepository.save(any()) } answers { firstArg<Subscription>().also { stored = it } }
    }

    private fun attrs(vararg kv: Pair<String, String>) = kv.associate { (k, v) -> k to RevenueCatAttribute(value = v, updatedAtMs = 1L) }

    private fun trial(appUserId: String = "301", attributes: Map<String, RevenueCatAttribute>? = null) = RevenueCatEvent(
        type = "INITIAL_PURCHASE",
        id = "evt-1",
        appUserId = appUserId,
        productId = "pro_yearly",
        periodType = "TRIAL",
        purchasedAtMs = 1_762_500_000_000,
        expirationAtMs = 1_763_100_000_000,
        eventTimestampMs = 1_762_500_001_000,
        store = "APP_STORE",
        environment = "PRODUCTION",
        transactionId = "txn-ad-1",
        subscriberAttributes = attributes,
    )

    @Test
    fun `광고 귀속이 있는 체험 시작은 캠페인·키워드와 함께 app_events 에 남는다`() {
        val saved = slot<AppEvent>()
        every { appEventRepository.save(capture(saved)) } answers { saved.captured }

        service.handleWebhookEvent(RevenueCatWebhookEvent(event = trial(attributes = attrs(
            "\$mediaSource" to "Apple Search Ads",
            "\$campaign" to "LiftUp KR 검색 테스트 2026-11",
            "\$adGroup" to "1 AI 의도 · 정확",
            "\$keyword" to "AI 퍼스널 트레이너",
            "\$email" to "x@example.com",
        ))))

        val row = saved.captured
        assertEquals(301L, row.userId)
        assertEquals(RevenueCatWebhookService.AD_ATTRIBUTION_EVENT, row.name)
        assertEquals("ios", row.platform)
        assertNotNull(row.occurredAt)
        @Suppress("UNCHECKED_CAST")
        val p = ObjectMapper().readValue(row.params, Map::class.java) as Map<String, String>
        assertEquals("Apple Search Ads", p["media_source"])
        assertEquals("LiftUp KR 검색 테스트 2026-11", p["campaign"])
        assertEquals("AI 퍼스널 트레이너", p["keyword"])
        assertEquals("INITIAL_PURCHASE", p["event_type"])
        assertEquals("TRIAL", p["period_type"])
        assertEquals("evt-1", p["event_id"])
        assertNull(p["\$email"], "귀속 외 속성(이메일 등)은 남기지 않는다")
        assertNotNull(stored, "구독 처리는 그대로")
    }

    @Test
    fun `귀속 속성이 없거나 비어 있으면 남기지 않는다`() {
        service.handleWebhookEvent(RevenueCatWebhookEvent(event = trial(attributes = null)))
        service.handleWebhookEvent(RevenueCatWebhookEvent(event = trial(attributes = attrs("\$mediaSource" to "  ", "\$email" to "a@b.c"))))
        verify(exactly = 0) { appEventRepository.save(any()) }
    }

    @Test
    fun `익명 사용자 id 는 남기지 않는다`() {
        assertNull(service.adAttribution(trial(appUserId = "\$RCAnonymousID:abc", attributes = attrs("\$mediaSource" to "Apple Search Ads"))))
    }

    @Test
    fun `긴 값은 잘라 params 컬럼 한도 안에 둔다`() {
        val long = "가".repeat(500)
        val row = service.adAttribution(trial(attributes = attrs(
            "\$mediaSource" to long, "\$campaign" to long, "\$adGroup" to long, "\$keyword" to long,
        )))!!
        assert(row.params!!.length <= 2000) { "params ${row.params!!.length}자" }
    }

    @Test
    fun `귀속 기록이 실패해도 구독 처리는 끝난다`() {
        every { appEventRepository.save(any()) } throws RuntimeException("db down")
        assertDoesNotThrow {
            service.handleWebhookEvent(RevenueCatWebhookEvent(event = trial(attributes = attrs("\$mediaSource" to "Apple Search Ads"))))
        }
        assertNotNull(stored)
    }

    @Test
    fun `실제 웹훅 JSON 을 운영과 같은 SNAKE_CASE 매퍼로 읽어도 귀속 속성이 살아 있다`() {
        // RevenueCat 웹훅 형식(이벤트 필드 + subscriber_attributes 의 {value, updated_at_ms}). 전역 매퍼가 SNAKE_CASE 라
        // 이름을 명시하지 않은 필드는 조용히 null 이 된다(2026-10-10 함정) — 운영 설정 그대로 읽어 본다.
        val mapper = jacksonObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        val body = """
            {"api_version":"1.0","event":{"type":"INITIAL_PURCHASE","id":"CDFD6D3A-1",
             "app_user_id":"301","original_app_user_id":"301","product_id":"pro_yearly","period_type":"TRIAL",
             "purchased_at_ms":1762500000000,"expiration_at_ms":1763100000000,"event_timestamp_ms":1762500001000,
             "environment":"PRODUCTION","store":"APP_STORE","transaction_id":"2000000","original_transaction_id":"2000000",
             "price":0.0,"price_in_purchased_currency":0.0,"currency":"KRW","is_family_share":false,
             "subscriber_attributes":{
               "${'$'}mediaSource":{"value":"Apple Search Ads","updated_at_ms":1762499000000},
               "${'$'}campaign":{"value":"LiftUp KR 검색 테스트 2026-11","updated_at_ms":1762499000000},
               "${'$'}keyword":{"value":"AI PT","updated_at_ms":1762499000000}}}}
        """.trimIndent()
        val event = mapper.readValue(body, RevenueCatWebhookEvent::class.java).event!!
        assertEquals("CDFD6D3A-1", event.id)
        assertEquals("Apple Search Ads", event.subscriberAttributes?.get("\$mediaSource")?.value)
        assertEquals(1762499000000, event.subscriberAttributes?.get("\$keyword")?.updatedAtMs)
        val row = service.adAttribution(event)!!
        assertEquals("ios", row.platform)
        assert(row.params!!.contains("\"keyword\":\"AI PT\"")) { row.params!! }
    }
}
