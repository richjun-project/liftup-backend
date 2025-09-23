package com.richjun.liftupai.domain.chat.entity

enum class MessageType {
    TEXT,
    IMAGE,
    WORKOUT_LOG,
    MEAL_LOG,
    SYSTEM  // PT 스타일 자동 메시지
}

enum class MessageStatus {
    PROCESSING,
    COMPLETED,
    FAILED
}