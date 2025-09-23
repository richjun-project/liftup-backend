package com.richjun.liftupai.domain.subscription.service

import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.subscription.dto.*
import com.richjun.liftupai.domain.subscription.entity.*
import com.richjun.liftupai.domain.subscription.repository.PaymentHistoryRepository
import com.richjun.liftupai.domain.subscription.repository.SubscriptionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
@Transactional
class SubscriptionService(
    private val userRepository: UserRepository,
    private val subscriptionRepository: SubscriptionRepository,
    private val paymentHistoryRepository: PaymentHistoryRepository
) {

    @Transactional(readOnly = true)
    fun getSubscriptionStatus(userId: Long): SubscriptionStatusResponse {
        val subscription = subscriptionRepository.findByUser_Id(userId)
            .orElse(createDefaultSubscription(userId))

        checkAndUpdateExpiredSubscription(subscription)

        return SubscriptionStatusResponse(
            plan = subscription.plan.displayName,
            status = subscription.status.name,
            expiryDate = subscription.expiryDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            features = subscription.plan.features,
            autoRenew = subscription.autoRenew
        )
    }

    fun subscribe(userId: Long, request: SubscribeRequest): SubscribeResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

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
            message = "구독이 성공적으로 완료되었습니다"
        )
    }

    fun cancelSubscription(userId: Long): CancelSubscriptionResponse {
        val subscription = subscriptionRepository.findByUser_Id(userId)
            .orElseThrow { ResourceNotFoundException("구독 정보를 찾을 수 없습니다") }

        if (subscription.status != SubscriptionStatus.ACTIVE) {
            throw IllegalStateException("활성 구독만 취소할 수 있습니다")
        }

        subscription.status = SubscriptionStatus.CANCELLED
        subscription.cancelledAt = LocalDateTime.now()
        subscription.autoRenew = false

        subscriptionRepository.save(subscription)

        return CancelSubscriptionResponse(
            success = true,
            cancelDate = subscription.cancelledAt!!.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            message = "구독이 취소되었습니다. 만료일까지 서비스를 이용하실 수 있습니다."
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
                message = "지원하지 않는 플랫폼입니다"
            )
        }
    }

    private fun createDefaultSubscription(userId: Long): Subscription {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        return Subscription(
            user = user,
            plan = SubscriptionPlan.FREE,
            status = SubscriptionStatus.ACTIVE,
            startDate = LocalDateTime.now()
        )
    }

    private fun checkAndUpdateExpiredSubscription(subscription: Subscription) {
        if (subscription.expiryDate != null &&
            subscription.expiryDate!!.isBefore(LocalDateTime.now()) &&
            subscription.status == SubscriptionStatus.ACTIVE) {

            subscription.status = SubscriptionStatus.EXPIRED
            subscription.plan = SubscriptionPlan.FREE
            subscriptionRepository.save(subscription)
        }
    }

    private fun updateSubscription(subscription: Subscription, request: SubscribeRequest): Subscription {
        subscription.plan = request.plan
        subscription.status = SubscriptionStatus.ACTIVE
        subscription.startDate = LocalDateTime.now()
        subscription.expiryDate = calculateExpiryDate(request.plan)
        subscription.googlePlayOrderId = request.googlePlayOrderId
        subscription.appleTransactionId = request.appleTransactionId
        subscription.autoRenew = true
        subscription.updatedAt = LocalDateTime.now()
        return subscription
    }

    private fun createNewSubscription(user: com.richjun.liftupai.domain.auth.entity.User, request: SubscribeRequest): Subscription {
        return Subscription(
            user = user,
            plan = request.plan,
            status = SubscriptionStatus.ACTIVE,
            startDate = LocalDateTime.now(),
            expiryDate = calculateExpiryDate(request.plan),
            googlePlayOrderId = request.googlePlayOrderId,
            appleTransactionId = request.appleTransactionId,
            autoRenew = true
        )
    }

    private fun calculateExpiryDate(plan: SubscriptionPlan): LocalDateTime? {
        return when (plan) {
            SubscriptionPlan.FREE -> null
            SubscriptionPlan.BASIC, SubscriptionPlan.PREMIUM -> LocalDateTime.now().plusMonths(1)
        }
    }

    private fun toSubscriptionInfo(subscription: Subscription): SubscriptionInfo {
        return SubscriptionInfo(
            id = subscription.id,
            plan = subscription.plan.displayName,
            status = subscription.status.name,
            startDate = subscription.startDate.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            expiryDate = subscription.expiryDate?.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            features = subscription.plan.features,
            autoRenew = subscription.autoRenew
        )
    }

    private fun validateGooglePlayReceipt(request: ValidateReceiptRequest): ValidateReceiptResponse {
        // 실제 구현에서는 Google Play Developer API를 사용하여 영수증 검증
        // 여기서는 간단한 모의 구현
        return ValidateReceiptResponse(
            valid = true,
            productId = request.productId,
            transactionId = "google_${System.currentTimeMillis()}",
            expiryDate = LocalDateTime.now().plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            message = "Google Play 영수증 검증 성공"
        )
    }

    private fun validateAppStoreReceipt(request: ValidateReceiptRequest): ValidateReceiptResponse {
        // 실제 구현에서는 App Store Receipt Validation API를 사용하여 영수증 검증
        // 여기서는 간단한 모의 구현
        return ValidateReceiptResponse(
            valid = true,
            productId = request.productId,
            transactionId = "apple_${System.currentTimeMillis()}",
            expiryDate = LocalDateTime.now().plusMonths(1).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            message = "App Store 영수증 검증 성공"
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
}