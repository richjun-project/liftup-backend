package com.richjun.liftupai.domain.subscription.controller

import com.richjun.liftupai.domain.subscription.dto.*
import com.richjun.liftupai.domain.subscription.service.SubscriptionService
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/subscription")
class SubscriptionController(
    private val subscriptionService: SubscriptionService
) {

    @GetMapping("/status")
    fun getSubscriptionStatus(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<SubscriptionStatusResponse>> {
        val response = subscriptionService.getSubscriptionStatus(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/subscribe")
    fun subscribe(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: SubscribeRequest
    ): ResponseEntity<ApiResponse<SubscribeResponse>> {
        val response = subscriptionService.subscribe(userDetails.getId(), request)
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(ApiResponse.success(response))
    }

    @PostMapping("/cancel")
    fun cancelSubscription(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<CancelSubscriptionResponse>> {
        val response = subscriptionService.cancelSubscription(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @PostMapping("/validate-receipt")
    fun validateReceipt(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ValidateReceiptRequest
    ): ResponseEntity<ApiResponse<ValidateReceiptResponse>> {
        val response = subscriptionService.validateReceipt(request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}