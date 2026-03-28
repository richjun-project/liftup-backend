package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.*
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.notification.util.NotificationScheduleTimeCalculator
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.LocalTime

@Service
@Transactional
class PTScheduledMessageService(
    private val userRepository: UserRepository,
    private val userProfileRepository: UserProfileRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationHistoryRepository: NotificationHistoryRepository,
    private val workoutSessionRepository: WorkoutSessionRepository,
    private val chatMessageRepository: ChatMessageRepository,
    private val notificationService: NotificationService,
    private val ptMessageTemplates: PTMessageTemplates
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 사용자의 PT 스타일에 맞는 스케줄 메시지 생성
     */
    fun createPTSchedulesForUser(userId: Long) {
        val locale = resolveLocale(userId)
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val profile = userProfileRepository.findByUser(user)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.profile_not_found", locale)) }

        val settings = userSettingsRepository.findByUser(user).orElse(null)

        val ptStyle = profile.ptStyle
        val preferredWorkoutTime = parseWorkoutTime(
            settings?.preferredWorkoutTime ?: profile.preferredWorkoutTime ?: "evening"
        )

        logger.info("Creating PT schedules for user ${user.id} with style $ptStyle")

        // 기존 스케줄 삭제
        deleteExistingPTSchedules(user)

        // 1. 아침 식사 체크 (07:30)
        createSchedule(
            user = user,
            scheduleName = "morning_meal_check",
            time = LocalTime.of(7, 30),
            message = ptMessageTemplates.getMorningMealMessage(ptStyle, locale),
            days = getAllDays()
        )

        // 2. 점심 식사 체크 (12:30)
        createSchedule(
            user = user,
            scheduleName = "lunch_meal_check",
            time = LocalTime.of(12, 30),
            message = ptMessageTemplates.getLunchMealMessage(ptStyle, locale),
            days = getAllDays()
        )

        // 3. 운동 30분 전 알림
        val workoutReminderTime = preferredWorkoutTime.minusMinutes(30)
        createSchedule(
            user = user,
            scheduleName = "workout_reminder",
            time = workoutReminderTime,
            message = ptMessageTemplates.getWorkoutReminderMessage(ptStyle, locale),
            days = getAllDays()
        )

        // 4. 저녁 식사 관리 (19:00)
        createSchedule(
            user = user,
            scheduleName = "dinner_meal_check",
            time = LocalTime.of(19, 0),
            message = ptMessageTemplates.getDinnerMealMessage(ptStyle, locale),
            days = getAllDays()
        )

        // 5. 수면 준비 메시지 (22:00)
        createSchedule(
            user = user,
            scheduleName = "sleep_prep",
            time = LocalTime.of(22, 0),
            message = ptMessageTemplates.getSleepPrepMessage(ptStyle, locale),
            days = getAllDays()
        )

        logger.info("Successfully created 5 PT schedules for user ${user.id}")
    }

    /**
     * PT 스타일 변경 시 스케줄 재생성
     */
    fun updatePTSchedulesForStyleChange(userId: Long, newStyle: PTStyle) {
        logger.info("Updating PT schedules for user $userId with new style $newStyle")
        val locale = resolveLocale(userId)

        // 사용자 프로필 조회
        val user = userRepository.findById(userId)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.user_not_found", locale)) }

        val profile = userProfileRepository.findByUser(user)
            .orElseThrow { ResourceNotFoundException(NotificationLocalization.message("error.profile_not_found", locale)) }

        // PT 스타일 업데이트
        profile.ptStyle = newStyle
        profile.updatedAt = AppTime.utcNow()
        userProfileRepository.save(profile)

        logger.info("PT style updated to $newStyle for user $userId")

        // 스케줄 재생성
        createPTSchedulesForUser(userId)
    }

    /**
     * 스케줄 ID로 조회하여 독립 트랜잭션에서 메시지 저장 + FCM 전송
     */
    fun sendScheduledPTMessage(scheduleId: Long) {
        val schedule = notificationScheduleRepository.findById(scheduleId).orElse(null)
        if (schedule == null) {
            logger.warn("[PTMessage] Schedule $scheduleId not found, skipping")
            return
        }

        try {
            // 1. ChatMessage에 저장 (채팅 히스토리에 표시)
            val chatMessage = ChatMessage(
                user = schedule.user,
                userMessage = "", // 시스템 메시지는 userMessage 비움
                aiResponse = schedule.message,
                messageType = MessageType.SYSTEM,
                status = MessageStatus.COMPLETED
            )
            chatMessageRepository.save(chatMessage)
            logger.info("[PTMessage] ChatMessage saved for user ${schedule.user.id}, schedule $scheduleId")

            // 2. FCM 전송 및 NotificationHistory 저장 (data를 일반 Map으로 복사)
            notificationService.sendScheduledNotificationWithFcm(schedule)

            logger.info("[PTMessage] PT message sent and saved to chat for user ${schedule.user.id}")
        } catch (e: Exception) {
            logger.error("[PTMessage] Failed to send PT message for schedule ${schedule.id}: ${e.message}", e)
        }
    }

    /**
     * 컨텍스트 기반 메시지 생성 (운동 이력 참고)
     */
    fun generateContextualMessage(user: User, baseMessage: String): String {
        val locale = resolveLocale(user.id)
        val recentSessions = workoutSessionRepository.findTop7ByUserOrderByStartTimeDesc(user)

        if (recentSessions.isEmpty()) {
            return NotificationLocalization.message("pt.context.no_history", locale, baseMessage)
        }

        val daysSinceLastWorkout = if (recentSessions.isNotEmpty()) {
            java.time.Duration.between(recentSessions.first().startTime, AppTime.utcNow()).toDays()
        } else {
            999
        }

        return when {
            daysSinceLastWorkout == 0L -> NotificationLocalization.message("pt.context.today_done", locale, baseMessage)
            daysSinceLastWorkout == 1L -> NotificationLocalization.message("pt.context.yesterday_done", locale, baseMessage)
            daysSinceLastWorkout >= 3L -> NotificationLocalization.message("pt.context.days_resting", locale, baseMessage, daysSinceLastWorkout)
            else -> baseMessage
        }
    }

    private fun createSchedule(
        user: User,
        scheduleName: String,
        time: LocalTime,
        message: String,
        days: Set<DayOfWeek>
    ) {
        val schedule = NotificationSchedule(
            user = user,
            scheduleName = scheduleName,
            time = time,
            message = message,
            days = days.toMutableSet(),
            enabled = true,
            notificationType = NotificationType.AI_MESSAGE,
            nextTriggerAt = NotificationScheduleTimeCalculator.calculateNextTrigger(days, time, resolveTimeZone(user.id))
        )
        notificationScheduleRepository.save(schedule)
    }

    private fun deleteExistingPTSchedules(user: User) {
        val existingSchedules = notificationScheduleRepository.findByUser(user)
            .filter { it.notificationType == NotificationType.AI_MESSAGE }

        if (existingSchedules.isNotEmpty()) {
            // Clear foreign key references in notification_history before deleting schedules
            val scheduleIds = existingSchedules.map { it.id }
            notificationHistoryRepository.clearScheduleReferences(scheduleIds)

            // Now safe to delete the schedules
            notificationScheduleRepository.deleteAll(existingSchedules)
        }
    }

    private fun parseWorkoutTime(preferredTime: String): LocalTime {
        return when (preferredTime.lowercase()) {
            "morning" -> LocalTime.of(7, 0)
            "afternoon" -> LocalTime.of(14, 0)
            "evening" -> LocalTime.of(18, 0)
            else -> LocalTime.of(18, 0)
        }
    }

    private fun getAllDays(): Set<DayOfWeek> {
        return setOf(
            DayOfWeek.MON,
            DayOfWeek.TUE,
            DayOfWeek.WED,
            DayOfWeek.THU,
            DayOfWeek.FRI,
            DayOfWeek.SAT,
            DayOfWeek.SUN
        )
    }

    private fun resolveLocale(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.language ?: "en"
    }

    private fun resolveTimeZone(userId: Long): String {
        return userSettingsRepository.findByUser_Id(userId).orElse(null)?.timeZone ?: AppTime.DEFAULT_TIME_ZONE
    }
}
