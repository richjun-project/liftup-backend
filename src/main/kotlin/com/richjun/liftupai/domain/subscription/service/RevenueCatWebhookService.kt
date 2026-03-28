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

        subscription.plan = SubscriptionPlan.PRO
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.startDate = msToLocalDateTime(event.purchasedAtMs) ?: AppTime.utcNow()
        subscription.expiryDate = msToLocalDateTime(event.expirationAtMs)
        subscription.autoRenew = event.periodType != "NON_RENEWING"
        subscription.updatedAt = AppTime.utcNow()

        subscriptionRepository.save(subscription)
        savePaymentHistory(user, subscription, event, PaymentStatus.SUCCESS)
        log.info("[RevenueCat] Purchase activated for user $userId, product=${event.productId}")
    }

    private fun handleRenewal(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            subscription.status = SubscriptionStatus.ACTIVE
            subscription.expiryDate = msToLocalDateTime(event.expirationAtMs)
            subscription.autoRenew = true
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

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            subscription.autoRenew = false
            subscription.cancelledAt = AppTime.utcNow()
            subscription.updatedAt = AppTime.utcNow()
            subscriptionRepository.save(subscription)
            log.info("[RevenueCat] Cancellation for user $userId, access until ${subscription.expiryDate}")
        }
    }

    private fun handleUncancellation(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
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
            val history = PaymentHistory(
                user = user,
                subscription = subscription,
                amount = event.price?.toInt() ?: 0,
                currency = event.currency ?: "USD",
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
