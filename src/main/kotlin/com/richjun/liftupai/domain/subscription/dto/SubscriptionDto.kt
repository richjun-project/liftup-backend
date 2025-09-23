package com.richjun.liftupai.domain.subscription.dto

import com.richjun.liftupai.domain.subscription.entity.SubscriptionPlan
import com.richjun.liftupai.domain.subscription.entity.SubscriptionStatus
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull

data class SubscriptionStatusResponse(
    val plan: String,
    val status: String,
    val expiryDate: String?,
    val features: List<String>,
    val autoRenew: Boolean
)

data class SubscribeRequest(
    @field:NotNull
    val plan: SubscriptionPlan,

    @field:NotBlank
    val paymentMethod: String,

    val receiptData: String? = null,

    val googlePlayOrderId: String? = null,

    val appleTransactionId: String? = null
)

data class SubscribeResponse(
    val success: Boolean,
    val subscription: SubscriptionInfo,
    val message: String? = null
)

data class SubscriptionInfo(
    val id: Long,
    val plan: String,
    val status: String,
    val startDate: String,
    val expiryDate: String?,
    val features: List<String>,
    val autoRenew: Boolean
)

data class CancelSubscriptionResponse(
    val success: Boolean,
    val cancelDate: String,
    val message: String? = null
)

data class ValidateReceiptRequest(
    @field:NotBlank
    val platform: String,

    @field:NotBlank
    val receiptData: String,

    val productId: String? = null
)

data class ValidateReceiptResponse(
    val valid: Boolean,
    val productId: String?,
    val transactionId: String?,
    val expiryDate: String?,
    val message: String? = null
)