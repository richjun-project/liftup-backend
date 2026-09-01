package com.richjun.liftupai.domain.subscription.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class RevenueCatWebhookEvent(
    @JsonProperty("api_version")
    val apiVersion: String? = null,

    val event: RevenueCatEvent? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class RevenueCatEvent(
    val type: String,

    @JsonProperty("app_user_id")
    val appUserId: String,

    @JsonProperty("original_app_user_id")
    val originalAppUserId: String? = null,

    @JsonProperty("product_id")
    val productId: String? = null,

    @JsonProperty("entitlement_ids")
    val entitlementIds: List<String>? = null,

    @JsonProperty("period_type")
    val periodType: String? = null,

    @JsonProperty("purchased_at_ms")
    val purchasedAtMs: Long? = null,

    @JsonProperty("expiration_at_ms")
    val expirationAtMs: Long? = null,

    @JsonProperty("event_timestamp_ms")
    val eventTimestampMs: Long? = null,

    val environment: String? = null,

    val store: String? = null,

    @JsonProperty("transaction_id")
    val transactionId: String? = null,

    @JsonProperty("original_transaction_id")
    val originalTransactionId: String? = null,

    /** RevenueCat이 USD로 환산한 금액. 실제 청구액이 아니다. */
    val price: Double? = null,

    /** 사용자가 실제로 결제한 통화 기준 금액. currency와 짝을 이루는 값이며 이쪽이 정본이다. */
    @JsonProperty("price_in_purchased_currency")
    val priceInPurchasedCurrency: Double? = null,

    val currency: String? = null,

    /** CANCELLATION 이벤트의 사유. CUSTOMER_SUPPORT면 환불이다(별도 REFUND 타입이 없다). */
    @JsonProperty("cancel_reason")
    val cancelReason: String? = null,

    @JsonProperty("is_family_share")
    val isFamilyShare: Boolean? = false
)
