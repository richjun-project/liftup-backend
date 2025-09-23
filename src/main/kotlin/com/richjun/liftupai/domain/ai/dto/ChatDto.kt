package com.richjun.liftupai.domain.ai.dto

import jakarta.validation.constraints.NotBlank

// AI 채팅용 DTOs - domain/chat/dto/ChatDto.kt와는 별개
data class ChatRequest(
    @field:NotBlank(message = "메시지는 필수입니다")
    val message: String,

    val context: ChatContext? = null
)

data class ChatResponse(
    val reply: String,
    val timestamp: String,
    val messageId: String,
    val suggestions: List<String>? = null
)

data class ChatContext(
    val workoutType: String? = null,
    val currentExercise: String? = null,
    val sessionId: Long? = null,
    val difficulty: String? = null,
    val muscleGroup: String? = null,
    val userGoal: String? = null
)