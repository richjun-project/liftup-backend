package com.richjun.liftupai.domain.chat.dto

import com.fasterxml.jackson.annotation.JsonProperty
import jakarta.validation.constraints.NotBlank

data class ChatMessageRequest(
    @field:NotBlank(message = "메시지는 필수입니다")
    val message: String,

    val attachments: List<String>? = null
)

data class ChatMessageResponse(
    @JsonProperty("message_id")
    val messageId: Long,

    @JsonProperty("user_message")
    val userMessage: String,

    @JsonProperty("ai_response")
    val aiResponse: String,

    val timestamp: String
)

data class ChatHistoryResponse(
    val messages: List<ChatMessageDto>,

    @JsonProperty("has_more")
    val hasMore: Boolean,

    @JsonProperty("total_count")
    val totalCount: Long
)

data class ChatMessageDto(
    val id: Long,

    @JsonProperty("user_message")
    val userMessage: String,

    @JsonProperty("ai_response")
    val aiResponse: String? = null,

    @JsonProperty("message_type")
    val messageType: String,

    @JsonProperty("attachment_url")
    val attachmentUrl: String? = null,

    val timestamp: String,

    val status: String
)

data class ChatWorkoutRecommendationRequest(
    val duration: Int? = null,
    val equipment: String? = null,

    @JsonProperty("target_muscle")
    val targetMuscle: String? = null,

    val difficulty: String? = null
)

data class ChatWorkoutRecommendationResponse(
    @JsonProperty("message_id")
    val messageId: Long,

    @JsonProperty("user_message")
    val userMessage: String,

    @JsonProperty("workout_recommendation")
    val workoutRecommendation: com.richjun.liftupai.domain.ai.dto.AIWorkoutRecommendationResponse,

    val timestamp: String
)