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
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.util.WorkoutLocalization
import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.global.time.AppTime
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Service
@Transactional
class ChatService(
    private val chatMessageRepository: ChatMessageRepository,
    private val userRepository: UserRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val geminiAIService: GeminiAIService,
    private val aiAnalysisService: AIAnalysisService,
    private val objectMapper: ObjectMapper
) {

    fun sendMessage(userId: Long, request: ChatMessageRequest): ChatMessageResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }

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
            chatMessage.aiResponse = aiResponse
            chatMessage.responseTime = responseTime
            chatMessage.status = MessageStatus.COMPLETED
            chatMessage = chatMessageRepository.save(chatMessage)

            ChatMessageResponse(
                messageId = chatMessage.id,
                userMessage = chatMessage.userMessage,
                aiResponse = aiResponse,
                timestamp = AppTime.formatUtcRequired(chatMessage.timestamp)
            )
        } catch (e: Exception) {
            // 에러 발생 시 상태 업데이트
            chatMessage.status = MessageStatus.FAILED
            chatMessage.error = e.message
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
            .orElseThrow { ResourceNotFoundException("User not found") }

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
            .orElseThrow { ResourceNotFoundException("User not found") }

        chatMessageRepository.deleteAllByUser(user)
    }

    fun getAIWorkoutRecommendation(
        userId: Long,
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?,
        localeOverride: String?
    ): ChatWorkoutRecommendationResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException("User not found") }
        val locale = resolveLocale(userId, localeOverride)

        // 사용자 메시지 생성
        val userMessage = buildWorkoutRequestMessage(duration, equipment, targetMuscle, difficulty, locale)

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
                difficulty,
                locale
            )

            // 추천을 사용자 친화적인 텍스트로 변환
            val aiResponseText = formatWorkoutRecommendationAsText(workoutRecommendation, locale)

            // AI 응답으로 메시지 업데이트
            chatMessage.aiResponse = aiResponseText
            chatMessage.status = MessageStatus.COMPLETED
            chatMessage = chatMessageRepository.save(chatMessage)

            ChatWorkoutRecommendationResponse(
                messageId = chatMessage.id,
                userMessage = userMessage,
                workoutRecommendation = workoutRecommendation,
                timestamp = AppTime.formatUtcRequired(chatMessage.timestamp)
            )
        } catch (e: Exception) {
            // 에러 발생 시 상태 업데이트
            chatMessage.status = MessageStatus.FAILED
            chatMessage.error = e.message
            chatMessageRepository.save(chatMessage)
            throw e
        }
    }

    private fun buildWorkoutRequestMessage(
        duration: Int?,
        equipment: String?,
        targetMuscle: String?,
        difficulty: String?,
        locale: String
    ): String {
        val parts = mutableListOf<String>()

        parts.add(WorkoutLocalization.message("chat.request.title", locale))

        duration?.let { parts.add(WorkoutLocalization.message("chat.request.duration", locale, it)) }
        equipment?.let {
            parts.add(
                WorkoutLocalization.message(
                    "chat.request.equipment",
                    locale,
                    WorkoutLocalization.equipmentName(it, locale)
                )
            )
        }
        targetMuscle?.let {
            parts.add(
                WorkoutLocalization.message(
                    "chat.request.target",
                    locale,
                    WorkoutLocalization.targetDisplayName(it, locale)
                )
            )
        }
        difficulty?.let {
            parts.add(
                WorkoutLocalization.message(
                    "chat.request.difficulty",
                    locale,
                    WorkoutLocalization.difficultyDisplayName(it, locale)
                )
            )
        }

        return parts.joinToString(" | ")
    }

    private fun formatWorkoutRecommendationAsText(
        recommendation: com.richjun.liftupai.domain.ai.dto.AIWorkoutRecommendationResponse,
        locale: String
    ): String {
        val detail = recommendation.recommendation
        val sb = StringBuilder()

        // 헤더
        sb.appendLine("🎯 ${detail.name}")
        sb.appendLine()

        // 기본 정보
        sb.appendLine(WorkoutLocalization.message("chat.summary.duration", locale, detail.duration))
        sb.appendLine(WorkoutLocalization.message("chat.summary.difficulty", locale, detail.difficulty))
        if (detail.targetMuscles.isNotEmpty()) {
            sb.appendLine(WorkoutLocalization.message("chat.summary.target", locale, detail.targetMuscles.joinToString(", ")))
        }
        if (detail.equipment.isNotEmpty()) {
            sb.appendLine(WorkoutLocalization.message("chat.summary.equipment", locale, detail.equipment.joinToString(", ")))
        }
        sb.appendLine(WorkoutLocalization.message("chat.summary.calories", locale, detail.estimatedCalories))
        sb.appendLine()

        // 운동 리스트
        sb.appendLine(WorkoutLocalization.message("chat.section.plan", locale))
        detail.exercises.forEach { exercise ->
            sb.appendLine("${exercise.order}. ${exercise.name}")
            sb.appendLine(WorkoutLocalization.message("chat.set.line", locale, exercise.sets, exercise.reps, exercise.rest))
            exercise.suggestedWeight?.let {
                sb.appendLine(WorkoutLocalization.message("chat.weight.line", locale, it))
            }
        }
        sb.appendLine()

        // 코칭 메시지
        detail.coachingMessage?.let {
            sb.appendLine(WorkoutLocalization.message("chat.section.coaching", locale))
            sb.appendLine(it)
            sb.appendLine()
        }

        // 운동 포커스
        detail.workoutFocus?.let {
            sb.appendLine(WorkoutLocalization.message("chat.section.focus", locale))
            sb.appendLine(it)
            sb.appendLine()
        }

        // 팁
        if (detail.tips.isNotEmpty()) {
            sb.appendLine(WorkoutLocalization.message("chat.section.tips", locale))
            detail.tips.forEach { tip ->
                sb.appendLine("• $tip")
            }
            sb.appendLine()
        }

        // 진행 노트
        detail.progressionNote?.let {
            sb.appendLine(WorkoutLocalization.message("chat.section.next", locale))
            sb.appendLine(it)
            sb.appendLine()
        }

        // AI 인사이트
        recommendation.aiInsights?.let { insights ->
            sb.appendLine(WorkoutLocalization.message("chat.section.insights", locale))
            insights.workoutRationale?.let {
                sb.appendLine(WorkoutLocalization.message("chat.insight.rationale", locale, it))
            }
            insights.keyPoint?.let {
                sb.appendLine(WorkoutLocalization.message("chat.insight.key_point", locale, it))
            }
            insights.nextStep?.let {
                sb.appendLine(WorkoutLocalization.message("chat.insight.next", locale, it))
            }
        }

        return sb.toString().trim()
    }

    private fun resolveLocale(userId: Long, localeOverride: String?): String {
        if (!localeOverride.isNullOrBlank()) {
            return WorkoutLocalization.normalizeLocale(localeOverride)
        }

        return WorkoutLocalization.normalizeLocale(
            userSettingsRepository.findByUser_Id(userId).orElse(null)?.language
        )
    }

    private fun mapToDto(message: ChatMessage): ChatMessageDto {
        return ChatMessageDto(
            id = message.id,
            userMessage = message.userMessage,
            aiResponse = message.aiResponse,
            messageType = message.messageType.name,
            attachmentUrl = message.attachmentUrl,
            timestamp = AppTime.formatUtcRequired(message.timestamp),
            status = message.status.name
        )
    }
}
