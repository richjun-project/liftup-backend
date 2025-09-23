package com.richjun.liftupai.domain.chat.controller

import com.richjun.liftupai.domain.chat.dto.*
import com.richjun.liftupai.global.common.ApiResponse
import com.richjun.liftupai.global.security.CustomUserDetails
import com.richjun.liftupai.domain.chat.service.ChatService
import jakarta.validation.Valid
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/chat")
class ChatController(
    private val chatService: ChatService
) {

    @PostMapping("/send")
    fun sendMessage(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @Valid @RequestBody request: ChatMessageRequest
    ): ResponseEntity<ApiResponse<ChatMessageResponse>> {
        val response = chatService.sendMessage(userDetails.getId(), request)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @GetMapping("/history")
    fun getChatHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(defaultValue = "1") page: Int,
        @RequestParam(defaultValue = "20") limit: Int,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDateTime?
    ): ResponseEntity<ApiResponse<ChatHistoryResponse>> {
        val response = chatService.getChatHistory(userDetails.getId(), page, limit, date)
        return ResponseEntity.ok(ApiResponse.success(response))
    }

    @DeleteMapping("/clear")
    fun clearChatHistory(
        @AuthenticationPrincipal userDetails: CustomUserDetails
    ): ResponseEntity<ApiResponse<Map<String, Boolean>>> {
        chatService.clearChatHistory(userDetails.getId())
        return ResponseEntity.ok(ApiResponse.success(mapOf("success" to true)))
    }

    @GetMapping("/recommendations/workout")
    fun getAIWorkoutRecommendation(
        @AuthenticationPrincipal userDetails: CustomUserDetails,
        @RequestParam(required = false) duration: Int?,
        @RequestParam(required = false) equipment: String?,
        @RequestParam(required = false, name = "target_muscle") targetMuscle: String?,
        @RequestParam(required = false) difficulty: String?
    ): ResponseEntity<ApiResponse<ChatWorkoutRecommendationResponse>> {
        val response = chatService.getAIWorkoutRecommendation(
            userDetails.getId(),
            duration,
            equipment,
            targetMuscle,
            difficulty
        )
        return ResponseEntity.ok(ApiResponse.success(response))
    }
}