package com.richjun.liftupai.domain.chat.service

import com.richjun.liftupai.domain.chat.dto.*
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.ai.service.AIAnalysisService
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Service
@Transactional
class ChatService(
    private val chatMessageRepository: ChatMessageRepository,
    private val userRepository: UserRepository,
    private val geminiAIService: GeminiAIService,
    private val aiAnalysisService: AIAnalysisService,
    private val objectMapper: ObjectMapper
) {

    fun sendMessage(userId: Long, request: ChatMessageRequest): ChatMessageResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 사용자 메시지 저장
        var chatMessage = ChatMessage(
            user = user,
            userMessage = request.message,
            messageType = if (request.attachments.isNullOrEmpty()) MessageType.TEXT else MessageType.IMAGE,
            attachmentUrl = request.attachments?.firstOrNull(),
            status = MessageStatus.PROCESSING
        )

        chatMessage = chatMessageRepository.save(chatMessage)

        return try {
            val startTime = System.currentTimeMillis()

            // Gemini AI 응답 생성
            val aiResponse = geminiAIService.generateResponse(request.message, user)

            val responseTime = System.currentTimeMillis() - startTime

            // AI 응답으로 메시지 업데이트
            chatMessage = chatMessage.copy(
                aiResponse = aiResponse,
                responseTime = responseTime,
                status = MessageStatus.COMPLETED
            )
            chatMessage = chatMessageRepository.save(chatMessage)

            ChatMessageResponse(
                messageId = chatMessage.id,
                userMessage = chatMessage.userMessage,
                aiResponse = aiResponse,
                timestamp = chatMessage.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        } catch (e: Exception) {
            // 에러 발생 시 상태 업데이트
            chatMessage = chatMessage.copy(
                status = MessageStatus.FAILED,
                error = e.message
            )
            chatMessageRepository.save(chatMessage)
            throw e
        }
    }

    @Transactional(readOnly = true)
    fun getChatHistory(
        userId: Long,
        page: Int,
        limit: Int,
        date: LocalDateTime?
    ): ChatHistoryResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        val pageable = PageRequest.of(page - 1, limit, Sort.by(Sort.Direction.DESC, "timestamp"))

        val messagesPage = if (date != null) {
            val endDate = date.plusDays(1)
            chatMessageRepository.findByUserAndTimestampBetweenOrderByTimestampDesc(
                user, date, endDate, pageable
            )
        } else {
            chatMessageRepository.findByUserOrderByTimestampDesc(user, pageable)
        }

        val messages = messagesPage.content.map { mapToDto(it) }
        val totalCount = chatMessageRepository.countByUser(user)

        return ChatHistoryResponse(
            messages = messages,
            hasMore = messagesPage.hasNext(),
            totalCount = totalCount
        )
    }

    fun clearChatHistory(userId: Long) {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        chatMessageRepository.deleteAllByUser(user)
    }

    fun getAIWorkoutRecommendation(
        userId: Long,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?
    ): ChatWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("사용자를 찾을 수 없습니다") }

        // 사용자 메시지 생성
        val userMessage = buildWorkoutRequestMessage(duration, equipment, targetMuscle, difficulty)

        // 사용자 메시지를 채팅에 저장
        var chatMessage = ChatMessage(
            user = user,
            userMessage = userMessage,
            messageType = MessageType.TEXT,
            status = MessageStatus.PROCESSING
        )

        chatMessage = chatMessageRepository.save(chatMessage)

        return try {
            // AI 운동 추천 생성
            val workoutRecommendation = aiAnalysisService.getAIWorkoutRecommendation(
                userId,
                duration,
                equipment,
                targetMuscle,
                difficulty
            )

            // 추천을 사용자 친화적인 텍스트로 변환
            val aiResponseText = formatWorkoutRecommendationAsText(workoutRecommendation)

            // AI 응답으로 메시지 업데이트
            chatMessage = chatMessage.copy(
                aiResponse = aiResponseText,
                status = MessageStatus.COMPLETED
            )
            chatMessage = chatMessageRepository.save(chatMessage)

            ChatWorkoutRecommendationResponse(
                messageId = chatMessage.id,
                userMessage = userMessage,
                workoutRecommendation = workoutRecommendation,
                timestamp = chatMessage.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            )
        } catch (e: Exception) {
            // 에러 발생 시 상태 업데이트
            chatMessage = chatMessage.copy(
                status = MessageStatus.FAILED,
                error = e.message
            )
            chatMessageRepository.save(chatMessage)
            throw e
        }
    }

    private fun buildWorkoutRequestMessage(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?
    ): String {
        val parts = mutableListOf<String>()

        parts.add("AI 운동 추천 요청")

        duration?.let { parts.add("운동 시간: ${it}분") }
        equipment?.let { parts.add("장비: $it") }
        targetMuscle?.let { parts.add("타겟 근육: $it") }
        difficulty?.let { parts.add("난이도: $it") }

        return parts.joinToString(" | ")
    }

    private fun formatWorkoutRecommendationAsText(
        recommendation: com.richjun.liftupai.domain.ai.dto.AIWorkoutRecommendationResponse
    ): String {
        val detail = recommendation.recommendation
        val sb = StringBuilder()

        // 헤더
        sb.appendLine("🎯 ${detail.name}")
        sb.appendLine()

        // 기본 정보
        sb.appendLine("⏱ 운동 시간: ${detail.duration}분")
        sb.appendLine("💪 난이도: ${detail.difficulty}")
        if (detail.targetMuscles.isNotEmpty()) {
            sb.appendLine("🎯 타겟 근육: ${detail.targetMuscles.joinToString(", ")}")
        }
        if (detail.equipment.isNotEmpty()) {
            sb.appendLine("🏋️ 필요 장비: ${detail.equipment.joinToString(", ")}")
        }
        sb.appendLine("🔥 예상 칼로리: ${detail.estimatedCalories}kcal")
        sb.appendLine()

        // 운동 리스트
        sb.appendLine("📋 운동 프로그램:")
        detail.exercises.forEach { exercise ->
            sb.appendLine("${exercise.order}. ${exercise.name}")
            sb.appendLine("   - ${exercise.sets}세트 x ${exercise.reps}회 (휴식 ${exercise.rest}초)")
            exercise.suggestedWeight?.let {
                sb.appendLine("   - 권장 무게: ${it}kg")
            }
        }
        sb.appendLine()

        // 코칭 메시지
        detail.coachingMessage?.let {
            sb.appendLine("💬 코치의 한마디:")
            sb.appendLine(it)
            sb.appendLine()
        }

        // 운동 포커스
        detail.workoutFocus?.let {
            sb.appendLine("🎯 오늘의 포커스:")
            sb.appendLine(it)
            sb.appendLine()
        }

        // 팁
        if (detail.tips.isNotEmpty()) {
            sb.appendLine("💡 운동 팁:")
            detail.tips.forEach { tip ->
                sb.appendLine("• $tip")
            }
            sb.appendLine()
        }

        // 진행 노트
        detail.progressionNote?.let {
            sb.appendLine("📈 다음 단계:")
            sb.appendLine(it)
            sb.appendLine()
        }

        // AI 인사이트
        recommendation.aiInsights?.let { insights ->
            sb.appendLine("🧠 AI 분석:")
            insights.workoutRationale?.let {
                sb.appendLine("• 운동 구성 이유: $it")
            }
            insights.keyPoint?.let {
                sb.appendLine("• 핵심 포인트: $it")
            }
            insights.nextStep?.let {
                sb.appendLine("• 다음 목표: $it")
            }
        }

        return sb.toString().trim()
    }

    private fun mapToDto(message: ChatMessage): ChatMessageDto {
        return ChatMessageDto(
            id = message.id,
            userMessage = message.userMessage,
            aiResponse = message.aiResponse,
            messageType = message.messageType.name,
            attachmentUrl = message.attachmentUrl,
            timestamp = message.timestamp.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
            status = message.status.name
        )
    }
}

