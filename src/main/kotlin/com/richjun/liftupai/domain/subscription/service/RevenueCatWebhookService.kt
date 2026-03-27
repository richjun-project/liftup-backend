package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.RevenueCatEvent
import com.richjun.liftupai.domain.subscription.dto.RevenueCatWebhookEvent
import com.richjun.liftupai.domain.subscription.entity.*
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    @Value("\${revenuecat.webhook-secret:}")
    private val webhookSecret: String
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun validateAuthorization(authHeader: String?): Boolean {
        if (webhookSecret.isBlank()) return true
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
            else -> log.info("[RevenueCat] Unhandled event type: ${event.type}")
        }
    }

    private fun handlePurchase(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        val user = userRepository.findById(userId).orElse(null) ?: run {
            log.warn("[RevenueCat] User not found: $userId")
            return
        }

        val subscription = subscriptionRepository.findByUser(user).orElse(null)
            ?: Subscription(user = user)

        subscription.plan = SubscriptionPlan.PRO
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.startDate = msToLocalDateTime(event.purchasedAtMs) ?: AppTime.utcNow()
        subscription.expiryDate = msToLocalDateTime(event.expirationAtMs)
        subscription.autoRenew = event.periodType != "NON_RENEWING"
        subscription.updatedAt = AppTime.utcNow()

        subscriptionRepository.save(subscription)
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
            log.info("[RevenueCat] Renewal for user $userId, newExpiry=${subscription.expiryDate}")
        }
    }

    private fun handleCancellation(event: RevenueCatEvent) {
        val userId = parseUserId(event.appUserId) ?: return

        subscriptionRepository.findByUser_Id(userId).ifPresent { subscription ->
            subscription.autoRenew = false
            subscription.cancelledAt = AppTime.utcNow()
            subscription.updatedAt = AppTime.utcNow()
            // Keep ACTIVE until expiry - user still has access
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
        // Keep access active during grace period - just log
        log.warn("[RevenueCat] Billing issue for user $userId, product=${event.productId}")
    }

    private fun handleProductChange(event: RevenueCatEvent) {
        // Treat as new purchase with updated product
        handlePurchase(event)
    }

    private fun handleTransfer(event: RevenueCatEvent) {
        log.info("[RevenueCat] Transfer event: from=${event.originalAppUserId} to=${event.appUserId}")
        // Expire old user's subscription
        val oldUserId = parseUserId(event.originalAppUserId ?: return)
        if (oldUserId != null) {
            subscriptionRepository.findByUser_Id(oldUserId).ifPresent { subscription ->
                subscription.status = SubscriptionStatus.EXPIRED
                subscription.plan = SubscriptionPlan.FREE
                subscription.updatedAt = AppTime.utcNow()
                subscriptionRepository.save(subscription)
            }
        }
        // Activate for new user
        handlePurchase(event)
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
