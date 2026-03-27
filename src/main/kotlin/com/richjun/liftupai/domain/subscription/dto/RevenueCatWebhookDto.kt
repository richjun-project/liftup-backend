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

    val price: Double? = null,

    val currency: String? = null,

    @JsonProperty("is_family_share")
    val isFamilyShare: Boolean? = false
)
