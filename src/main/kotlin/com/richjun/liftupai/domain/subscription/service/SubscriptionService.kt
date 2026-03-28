package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.*
import com.richjun.liftupai.domain.subscription.entity.*
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.i18n.ErrorLocalization
import com.richjun.liftupai.global.time.AppTime
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class SubscriptionService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentHistoryRepository: PaymentHistoryRepository,
    private val userSettingsRepository: UserSettingsRepository
) {

    @Transactional
    fun getSubscriptionStatus(userId: Long): SubscriptionStatusResponse {
        val subscription = subscriptionRepository.findByUser_Id(userId)
            .orElse(createDefaultSubscription(userId))

        checkAndUpdateExpiredSubscription(subscription)

        return SubscriptionStatusResponse(
            plan = subscription.plan.displayName,
            status = subscription.status.name,
            expiryDate = AppTime.formatUtc(subscription.expiryDate),
            features = subscription.plan.features,
            autoRenew = subscription.autoRenew
        )
    }

    fun subscribe(userId: Long, request: SubscribeRequest): SubscribeResponse {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(ErrorLocalization.message("error.user_not_found", locale)) }

        val existingSubscription = subscriptionRepository.findByUser(user)

        val subscription = if (existingSubscription.isPresent) {
            val sub = existingSubscription.get()
            updateSubscription(sub, request)
        } else {
            createNewSubscription(user, request)
        }

        val savedSubscription = subscriptionRepository.save(subscription)

        val paymentHistory = PaymentHistory(
            user = user,
            subscription = savedSubscription,
            amount = request.plan.price,
            paymentMethod = request.paymentMethod,
            transactionId = request.googlePlayOrderId ?: request.appleTransactionId,
            receiptData = request.receiptData,
            status = PaymentStatus.SUCCESS
        )
        paymentHistoryRepository.save(paymentHistory)

        return SubscribeResponse(
            success = true,
            subscription = toSubscriptionInfo(savedSubscription),
            message = ErrorLocalization.message("subscription.success", locale)
        )
    }

    fun cancelSubscription(userId: Long): CancelSubscriptionResponse {
        val locale = resolveLocale(userId)
        val subscription = subscriptionRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException(ErrorLocalization.message("subscription.not_found", locale)) }

        if (subscription.status != SubscriptionStatus.ACTIVE) {
            throw IllegalStateException(ErrorLocalization.message("subscription.only_active_cancel", locale))
        }

        subscription.status = SubscriptionStatus.CANCELLED
        subscription.cancelledAt = AppTime.utcNow()
        subscription.autoRenew = false

        subscriptionRepository.save(subscription)

        return CancelSubscriptionResponse(
            success = true,
            cancelDate = AppTime.formatUtcRequired(subscription.cancelledAt!!),
            message = ErrorLocalization.message("subscription.cancelled", locale)
        )
    }

    fun validateReceipt(request: ValidateReceiptRequest): ValidateReceiptResponse {
        return when (request.platform.uppercase()) {
            "GOOGLE", "ANDROID" -> validateGooglePlayReceipt(request)
            "APPLE", "IOS" -> validateAppStoreReceipt(request)
            else -> ValidateReceiptResponse(
                valid = false,
                productId = null,
                transactionId = null,
                expiryDate = null,
                message = ErrorLocalization.message("subscription.unsupported_platform")
            )
        }
    }

    private fun createDefaultSubscription(userId: Long): Subscription {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(ErrorLocalization.message("error.user_not_found", locale)) }

        return Subscription(
            user = user,
            plan = SubscriptionPlan.FREE,
            status = SubscriptionStatus.ACTIVE,
            startDate = AppTime.utcNow()
        )
    }

    private fun checkAndUpdateExpiredSubscription(subscription: Subscription) {
        if (subscription.expiryDate != null &&
            subscription.expiryDate!!.isBefore(AppTime.utcNow()) &&
            subscription.status == SubscriptionStatus.ACTIVE) {

            subscription.status = SubscriptionStatus.EXPIRED
            subscription.plan = SubscriptionPlan.FREE
            subscriptionRepository.save(subscription)
        }
    }

    private fun updateSubscription(subscription: Subscription, request: SubscribeRequest): Subscription {
        subscription.plan = request.plan
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.startDate = AppTime.utcNow()
        subscription.expiryDate = calculateExpiryDate(request.plan)
        subscription.googlePlayOrderId = request.googlePlayOrderId
        subscription.appleTransactionId = request.appleTransactionId
        subscription.autoRenew = true
        subscription.updatedAt = AppTime.utcNow()
        return subscription
    }

    private fun createNewSubscription(user: com.richjun.liftupai.domain.auth.entity.User, request: SubscribeRequest): Subscription {
        return Subscription(
            user = user,
            plan = request.plan,
            status = SubscriptionStatus.ACTIVE,
            startDate = AppTime.utcNow(),
            expiryDate = calculateExpiryDate(request.plan),
            googlePlayOrderId = request.googlePlayOrderId,
            appleTransactionId = request.appleTransactionId,
            autoRenew = true
        )
    }

    private fun calculateExpiryDate(plan: SubscriptionPlan): LocalDateTime? {
        return when (plan) {
            SubscriptionPlan.FREE -> null
            SubscriptionPlan.BASIC, SubscriptionPlan.PREMIUM, SubscriptionPlan.PRO -> AppTime.utcNow().plusMonths(1)
        }
    }

    private fun toSubscriptionInfo(subscription: Subscription): SubscriptionInfo {
        return SubscriptionInfo(
            id = subscription.id,
            plan = subscription.plan.displayName,
            status = subscription.status.name,
            startDate = AppTime.formatUtcRequired(subscription.startDate),
            expiryDate = AppTime.formatUtc(subscription.expiryDate),
            features = subscription.plan.features,
            autoRenew = subscription.autoRenew
        )
    }

    private fun validateGooglePlayReceipt(request: ValidateReceiptRequest): ValidateReceiptResponse {
        // RevenueCat이 영수증 검증을 처리하므로 직접 검증하지 않음
        // 이 엔드포인트는 클라이언트에서 호출하지 않아야 함
        return ValidateReceiptResponse(
            valid = false,
            productId = request.productId,
            transactionId = null,
            expiryDate = null,
            message = "Use RevenueCat SDK for receipt validation"
        )
    }

    private fun validateAppStoreReceipt(request: ValidateReceiptRequest): ValidateReceiptResponse {
        return ValidateReceiptResponse(
            valid = false,
            productId = request.productId,
            transactionId = null,
            expiryDate = null,
            message = "Use RevenueCat SDK for receipt validation"
        )
    }

    @Transactional(readOnly = true)
    fun hasActiveSubscription(userId: Long): Boolean {
        val subscription = subscriptionRepository.findByUser_Id(userId).orElse(null)
            ?: return false

        return subscription.status == SubscriptionStatus.ACTIVE &&
               subscription.plan != SubscriptionPlan.FREE
    }

    @Transactional(readOnly = true)
    fun getCurrentPlan(userId: Long): SubscriptionPlan {
        val subscription = subscriptionRepository.findByUser_Id(userId).orElse(null)
            ?: return SubscriptionPlan.FREE

        if (subscription.status != SubscriptionStatus.ACTIVE) {
            return SubscriptionPlan.FREE
        }

        return subscription.plan
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }
}