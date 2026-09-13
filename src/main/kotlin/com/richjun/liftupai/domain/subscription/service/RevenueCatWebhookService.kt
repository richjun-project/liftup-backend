package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.RevenueCatEvent
import com.richjun.liftupai.domain.subscription.dto.RevenueCatWebhookEvent
import com.richjun.liftupai.domain.subscription.entity.*
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
@Transactional
class RevenueCatWebhookService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentHistoryRepository: PaymentHistoryRepository,
    @Value("\${revenuecat.webhook-secret:}")
    private val webhookSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validateAuthorization(authHeader: String?): Boolean {
        if (webhookSecret.isBlank()) {
            log.warn("[RevenueCat] Webhook secret not configured — rejecting request for security")
            return false
        }
        return authHeader == "Bearer $webhookSecret"
    }

    fun handleWebhookEvent(webhookEvent: RevenueCatWebhookEvent) {
        val event = webhookEvent.event ?: run {
            log.warn("[RevenueCat] Webhook received with null event")
            return
        }

        log.info("[RevenueCat] Webhook event: type=${event.type}, appUserId=${event.appUserId}, product=${event.productId}")

        when (event.type) {
            "INITIAL_PURCHASE", "NON_RENEWING_PURCHASE" -> handlePurchase(event)
            "RENEWAL" -> handleRenewal(event)
            "CANCELLATION" -> handleCancellation(event)
            "UNCANCELLATION" -> handleUncancellation(event)
            "EXPIRATION" -> handleExpiration(event)
            "BILLING_ISSUE" -> handleBillingIssue(event)
            "PRODUCT_CHANGE" -> handleProductChange(event)
            "TRANSFER" -> handleTransfer(event)
            "TEST" -> log.info("[RevenueCat] ✅ TEST webhook received — appUserId=${event.appUserId}, product=${event.productId}")
            else -> log.info("[RevenueCat] Unhandled event type: ${event.type}")
        }
    }

    // === 이벤트 핸들러 ===

    private fun handlePurchase(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return
        val user = findUser(userId) ?: return
        val subscription = findOrCreateSubscription(user)

        // 여러 구독이 겹칠 수 있다(스토어 구독 + promotional 평생 이용권).
        // 이용 기간이 더 긴(같거나) 쪽이 "대표 구독"이 되고, 만료일·출처·자동갱신은 대표 구독을 따른다.
        // 짧은 구독 이벤트가 긴 구독의 만료일/출처/자동갱신을 덮어쓰면 안 된다.
        val takesOver = takesOverAccess(subscription, event)
        subscription.plan = SubscriptionPlan.PRO
        subscription.status = SubscriptionStatus.ACTIVE
        if (takesOver) {
            subscription.startDate = msToLocalDateTime(event.purchasedAtMs) ?: AppTime.utcNow()
            subscription.expiryDate = if (isLifetime(event)) null else msToLocalDateTime(event.expirationAtMs)
            subscription.store = event.store ?: subscription.store
            subscription.autoRenew = !isNonRenewing(event)
        }
        subscription.updatedAt = AppTime.utcNow()

        subscriptionRepository.save(subscription)
        savePaymentHistory(user, subscription, event, PaymentStatus.SUCCESS)
        log.info("[RevenueCat] Purchase activated for user $userId, product=${event.productId}")
    }

    private fun handleRenewal(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            // handlePurchase 와 같은 대표 구독 규칙
            val takesOver = takesOverAccess(subscription, event)
            subscription.plan = SubscriptionPlan.PRO
            subscription.status = SubscriptionStatus.ACTIVE
            if (takesOver) {
                subscription.expiryDate = msToLocalDateTime(event.expirationAtMs) ?: subscription.expiryDate
                subscription.store = event.store ?: subscription.store
                subscription.autoRenew = true
            }
            subscription.updatedAt = AppTime.utcNow()
            subscriptionRepository.save(subscription)

            findUser(userId)?.let { user ->
                savePaymentHistory(user, subscription, event, PaymentStatus.SUCCESS)
            }
            log.info("[RevenueCat] Renewal for user $userId, newExpiry=${subscription.expiryDate}")
        }
    }

    private fun handleCancellation(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        // RevenueCat에는 REFUND 이벤트 타입이 없다. 환불은 CANCELLATION + cancel_reason=CUSTOMER_SUPPORT로 온다.
        // 이를 구분하지 않으면 환불된 사용자가 원래 만료일까지 PRO를 그대로 쓰게 된다.
        val isRefund = event.cancelReason?.uppercase() in setOf("CUSTOMER_SUPPORT", "REFUND")

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            // 해지·환불 이벤트는 개별 구독 단위로 온다. 더 긴 다른 구독(예: promotional 평생 이용권)이
            // 대표 구독이면 그 권한은 그대로 둔다 — 예전엔 월간 구독 환불이 평생 이용권까지 즉시 회수했다.
            if (longerAccessRemains(subscription, event)) {
                log.info(
                    "[RevenueCat] Ignoring ${if (isRefund) "refund" else "cancellation"} for user $userId — " +
                        "a longer subscription remains (store=${subscription.store}, expiry=${subscription.expiryDate})"
                )
                return@ifPresent
            }
            subscription.autoRenew = false
            subscription.cancelledAt = AppTime.utcNow()
            subscription.updatedAt = AppTime.utcNow()
            if (isRefund) {
                subscription.status = SubscriptionStatus.EXPIRED
                subscription.plan = SubscriptionPlan.FREE
                subscription.expiryDate = AppTime.utcNow()
            }
            subscriptionRepository.save(subscription)
            if (isRefund) {
                log.info("[RevenueCat] Refund for user $userId — access revoked immediately")
            } else {
                log.info("[RevenueCat] Cancellation for user $userId, access until ${subscription.expiryDate}")
            }
        }
    }

    private fun handleUncancellation(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            // 더 긴 다른 구독이 대표면 그 구독의 자동갱신 상태를 이 이벤트로 바꾸지 않는다
            if (longerAccessRemains(subscription, event)) {
                log.info("[RevenueCat] Uncancellation for user $userId ignored for representative subscription (store=${subscription.store})")
                return@ifPresent
            }
            subscription.autoRenew = true
            subscription.cancelledAt = null
            subscription.status = SubscriptionStatus.ACTIVE
            subscription.updatedAt = AppTime.utcNow()
            subscriptionRepository.save(subscription)
            log.info("[RevenueCat] Uncancellation for user $userId")
        }
    }

    private fun handleExpiration(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            // 사용자는 여러 소스(스토어 구독 + promotional 부여)로 같은 entitlement 를
            // 가질 수 있다. 만료 이벤트는 개별 구독 단위로 오므로, DB 에 저장된 만료일이
            // 이벤트의 만료 시점보다 미래라면 더 긴 다른 구독이 살아 있다는 뜻이다.
            // 이 가드가 없으면 월간 구독 만료 웹훅이 promotional 평생 이용권까지 강등시킨다.
            if (longerAccessRemains(subscription, event)) {
                log.info(
                    "[RevenueCat] Ignoring expiration for user $userId — a longer subscription remains " +
                        "(event expiry=${msToLocalDateTime(event.expirationAtMs)}, stored expiry=${subscription.expiryDate}, " +
                        "store=${subscription.store})"
                )
                return@ifPresent
            }

            subscription.status = SubscriptionStatus.EXPIRED
            subscription.plan = SubscriptionPlan.FREE
            subscription.autoRenew = false
            subscription.updatedAt = AppTime.utcNow()
            subscriptionRepository.save(subscription)
            log.info("[RevenueCat] Expiration for user $userId, downgraded to FREE")
        }
    }

    private fun handleBillingIssue(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            // 결제 문제는 해당 스토어 구독에만 해당한다 — 평생 이용권 같은 더 긴 권한은 건드리지 않는다
            if (longerAccessRemains(subscription, event)) {
                log.info("[RevenueCat] Billing issue for user $userId ignored — a longer subscription remains (store=${subscription.store})")
                return@ifPresent
            }
            subscription.status = SubscriptionStatus.PENDING
            subscription.updatedAt = AppTime.utcNow()
            subscriptionRepository.save(subscription)
            log.warn("[RevenueCat] Billing issue for user $userId — status set to PENDING (grace period)")
        }
    }

    private fun handleProductChange(event: RevenueCatEvent) {
        handlePurchase(event)
    }

    private fun handleTransfer(event: RevenueCatEvent) {
        log.info("[RevenueCat] Transfer event: from=${event.originalAppUserId} to=${event.appUserId}")

        // 이전 사용자 구독 만료 (originalAppUserId가 없어도 새 사용자 활성화는 진행)
        event.originalAppUserId?.let { originalId ->
            parseUserId(originalId)?.let { oldUserId ->
                subscriptionRepository.findByUser_Id(oldUserId).ifPresent { subscription ->
                    subscription.status = SubscriptionStatus.EXPIRED
                    subscription.plan = SubscriptionPlan.FREE
                    subscription.updatedAt = AppTime.utcNow()
                    subscriptionRepository.save(subscription)
                }
            }
        }

        // 새 사용자 활성화
        handlePurchase(event)
    }

    // === 대표 구독 판정 ===

    /** 갱신되지 않는 이용권: 일회성 구매, promotional 부여 */
    private fun isNonRenewing(event: RevenueCatEvent): Boolean =
        event.type == "NON_RENEWING_PURCHASE" ||
            event.periodType?.uppercase() in setOf("NON_RENEWING", "PROMOTIONAL") ||
            event.store?.uppercase() == "PROMOTIONAL"

    /** 만료일이 없는 갱신 안 되는 이용권 = 평생 이용권 */
    private fun isLifetime(event: RevenueCatEvent): Boolean =
        isNonRenewing(event) && event.expirationAtMs == null

    /** 저장된 구독이 지금 PRO 권한을 주고 있는가 */
    private fun hasActiveAccess(subscription: Subscription): Boolean =
        subscription.status == SubscriptionStatus.ACTIVE && subscription.plan != SubscriptionPlan.FREE

    /**
     * 이 구매/갱신 이벤트가 대표 구독이 되는가 — 이용 기간이 현재 대표 구독보다 길거나 같을 때.
     * 만료일 null + 활성 PRO 는 평생 이용권이므로 무엇도 이를 넘지 못한다(다른 평생 이용권만 동등).
     */
    internal fun takesOverAccess(subscription: Subscription, event: RevenueCatEvent): Boolean {
        if (!hasActiveAccess(subscription)) return true
        val stored = subscription.expiryDate
        if (isLifetime(event)) return true
        if (stored == null) return false
        val incoming = msToLocalDateTime(event.expirationAtMs) ?: return false
        return !incoming.isBefore(stored)
    }

    /** 이 해지/만료/환불 이벤트와 무관한 더 긴 구독이 권한을 주고 있는가 */
    internal fun longerAccessRemains(subscription: Subscription, event: RevenueCatEvent): Boolean {
        if (!hasActiveAccess(subscription)) return false
        val stored = subscription.expiryDate
        val eventExpiry = msToLocalDateTime(event.expirationAtMs)
        // 평생 이용권이 대표인데 다른 출처의 이벤트가 왔다
        if (stored == null) {
            return subscription.store?.uppercase() == "PROMOTIONAL" && event.store?.uppercase() != "PROMOTIONAL"
        }
        return eventExpiry != null && stored.isAfter(eventExpiry)
    }

    // === 헬퍼 메서드 ===

    private fun findUser(userId: Long): User? {
        return userRepository.findById(userId).orElse(null).also {
            if (it == null) log.warn("[RevenueCat] User not found: $userId")
        }
    }

    /**
     * 동시 webhook에 의한 중복 생성 방지:
     * findByUser가 null이면 새로 생성 후 save, unique 제약 위반 시 재조회
     */
    private fun findOrCreateSubscription(user: User): Subscription {
        subscriptionRepository.findByUser(user).orElse(null)?.let { return it }

        return try {
            subscriptionRepository.save(Subscription(user = user))
        } catch (e: DataIntegrityViolationException) {
            log.info("[RevenueCat] Concurrent subscription creation detected, re-fetching")
            subscriptionRepository.findByUser(user)
                .orElseThrow { IllegalStateException("Subscription not found after concurrent creation") }
        }
    }

    private fun savePaymentHistory(user: User, subscription: Subscription, event: RevenueCatEvent, status: PaymentStatus) {
        try {
            // 웹훅 재전송 멱등성: 같은 transactionId 이력이 이미 있으면 스킵
            // (DB 유니크 제약 uk_payment_history_transaction_id이 백스톱)
            val txId = event.transactionId
            if (txId != null && paymentHistoryRepository.findByTransactionId(txId) != null) {
                log.info("[RevenueCat] Payment history for transaction $txId already exists — skipping duplicate")
                return
            }
            val history = PaymentHistory(
                user = user,
                subscription = subscription,
                // event.price는 USD 환산가라 실제 청구액이 아니다.
                // 실결제 통화 금액(price_in_purchased_currency)이 있으면 그것과 currency를 짝으로 쓰고,
                // 없을 때만 USD 환산가로 폴백한다(통화 표기도 USD로 맞춘다).
                amount = event.priceInPurchasedCurrency ?: event.price ?: 0.0,
                currency = if (event.priceInPurchasedCurrency != null) (event.currency ?: "KRW") else "USD",
                paymentMethod = event.store ?: "UNKNOWN",
                transactionId = event.transactionId,
                status = status,
                paymentDate = msToLocalDateTime(event.purchasedAtMs) ?: AppTime.utcNow()
            )
            paymentHistoryRepository.save(history)
        } catch (e: Exception) {
            log.warn("[RevenueCat] Failed to save payment history: ${e.message}")
        }
    }

    private fun parseUserId(appUserId: String): Long? {
        return try {
            appUserId.toLong()
        } catch (e: NumberFormatException) {
            log.warn("[RevenueCat] Cannot parse user ID: $appUserId (anonymous or non-numeric)")
            null
        }
    }

    private fun msToLocalDateTime(ms: Long?): LocalDateTime? {
        if (ms == null) return null
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneOffset.UTC)
    }
}
