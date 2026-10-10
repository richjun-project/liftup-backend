package com.richjun.liftupai.domain.ai.dto

import jakarta.validation.constraints.NotBlank

// AI 채팅용 DTOs - domain/chat/dto/ChatDto.kt와는 별개
data class ChatRequest(
    @field:NotBlank(message = "Message is required")
    val message: String,

    val context: ChatContext? = null,

    /** 채팅에 첨부한 사진 URL(업로드 API 가 돌려준 것). 앱은 예전부터 보냈지만 서버가 받지 않아 트레이너가 사진을 못 봤다. 최대 3장만 쓴다. */
    val images: List<String>? = null
)

data class ChatResponse(
    val reply: String,
    val timestamp: String,
    val messageId: String,
    val suggestions: List<String>? = null,
    /** 답 아래에 보일 버튼(ChatAction 이름). 모르는 이름은 앱이 무시한다. */
    val actions: List<String> = emptyList()
)

data class ChatContext(
    val workoutType: String? = null,
    val currentExercise: String? = null,
    val sessionId: Long? = null,
    val difficulty: String? = null,
    val muscleGroup: String? = null,
    val userGoal: String? = null
)
