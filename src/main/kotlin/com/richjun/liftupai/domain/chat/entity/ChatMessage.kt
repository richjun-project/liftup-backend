package com.richjun.liftupai.domain.chat.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "chat_messages")
data class ChatMessage(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(columnDefinition = "TEXT", nullable = false)
    val userMessage: String,

    @Column(columnDefinition = "TEXT")
    var aiResponse: String? = null,

    @Enumerated(EnumType.STRING)
    val messageType: MessageType = MessageType.TEXT,

    @Column
    val attachmentUrl: String? = null,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now(),

    @Column
    var responseTime: Long? = null,  // AI 응답 시간 (밀리초)

    @Enumerated(EnumType.STRING)
    var status: MessageStatus = MessageStatus.PROCESSING,

    @Column
    var error: String? = null,

    /** 이 메시지 아래에 보일 버튼(ChatAction 이름, 쉼표 구분) */
    @Column(length = 255)
    var actions: String? = null,

    /** AI PT 가 이 답을 만들며 쓴 도구(이름[:fail][:changed], 쉼표 구분). 에이전트 경로만 채운다 */
    @Column(name = "tool_trace", length = 500)
    var toolTrace: String? = null
)