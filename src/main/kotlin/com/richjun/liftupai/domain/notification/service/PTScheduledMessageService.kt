package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.ai.service.GeminiAIService
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
import com.richjun.liftupai.domain.nutrition.entity.MealType
import com.richjun.liftupai.domain.nutrition.service.NutritionContextService
import com.richjun.liftupai.domain.subscription.service.SubscriptionService
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

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
    private val ptMessageTemplates: PTMessageTemplates,
    private val nutritionContextService: NutritionContextService,
    private val subscriptionService: SubscriptionService,
    private val geminiAIService: GeminiAIService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Pro 유저 동적 식단 알림 메시지 캐시.
     * key = "userId:mealType:date(localUTC)" → message
     * scheduler가 60초마다 돌고 due 판단 race가 있을 수 있으므로 5분 TTL.
     */
    private data class CachedMessage(val message: String, val cachedAtMillis: Long)
    private val mealMessageCache = ConcurrentHashMap<String, CachedMessage>()
    private val mealCacheTtlMillis = 5 * 60 * 1000L

    /** scheduleName → 끼니 매핑. null이면 식단 알림 아님 */
    private fun mealTypeForScheduleName(name: String): MealType? = when (name) {
        "morning_meal_check" -> MealType.BREAKFAST
        "lunch_meal_check" -> MealType.LUNCH
        "dinner_meal_check" -> MealType.DINNER
        else -> null
    }

    /** Pro 유저에게만 호출되는 동적 식단 코칭 메시지 생성 (캐시 + AI 호출 실패 시 fallback) */
    private fun resolveDynamicMealMessage(
        user: User,
        mealType: MealType,
        ptStyle: PTStyle,
        locale: String,
        fallback: String
    ): String {
        val cacheKey = "${user.id}:${mealType.name}:${LocalDate.now()}"
        val cached = mealMessageCache[cacheKey]
        if (cached != null && System.currentTimeMillis() - cached.cachedAtMillis < mealCacheTtlMillis) {
            return cached.message
        }
        return try {
            val ctx = nutritionContextService.buildTodayContext(user)
            val msg = geminiAIService.generateMealCoachingMessage(user, ptStyle, mealType, ctx, locale)
            val finalMsg = msg.ifBlank { fallback }
            mealMessageCache[cacheKey] = CachedMessage(finalMsg, System.currentTimeMillis())
            finalMsg
        } catch (e: Exception) {
            logger.warn("[PTMessage] dynamic meal message failed for user ${user.id} ($mealType): ${e.message}")
            fallback
        }
    }

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
     * 스케줄 ID로 조회하여 메시지 저장 + FCM 전송
     * 비관적 잠금으로 중복 발송 방지
     */
    fun sendScheduledPTMessage(scheduleId: Long) {
        // 비관적 잠금으로 조회 — 다른 스케줄러 스레드와 동시 처리 방지
        val schedule = notificationScheduleRepository.findByIdForUpdate(scheduleId)
        if (schedule == null) {
            logger.warn("[PTMessage] Schedule $scheduleId not found, skipping")
            return
        }

        // 이미 처리된 스케줄인지 확인 (nextTriggerAt이 미래이면 이미 처리됨)
        val now = AppTime.utcNow()
        if (schedule.nextTriggerAt != null && schedule.nextTriggerAt!!.isAfter(now)) {
            logger.info("[PTMessage] Schedule $scheduleId already processed (nextTriggerAt=${schedule.nextTriggerAt}), skipping")
            return
        }

        try {
            // 식단 알림(아침/점심/저녁 meal_check)이고 사용자가 Pro면 → 동적 메시지로 교체
            // Free 유저는 정적 메시지 그대로 사용 → AI 비용 0
            val mealType = mealTypeForScheduleName(schedule.scheduleName)
            val effectiveMessage = if (mealType != null && subscriptionService.hasActiveSubscription(schedule.user.id)) {
                val locale = resolveLocale(schedule.user.id)
                val ptStyle = userProfileRepository.findByUser(schedule.user).orElse(null)?.ptStyle
                    ?: PTStyle.GAME_MASTER
                resolveDynamicMealMessage(schedule.user, mealType, ptStyle, locale, schedule.message)
            } else {
                schedule.message
            }

            // 1. ChatMessage에 저장 (채팅 히스토리에 표시)
            val chatMessage = ChatMessage(
                user = schedule.user,
                userMessage = "",
                aiResponse = effectiveMessage,
                messageType = MessageType.SYSTEM,
                status = MessageStatus.COMPLETED
            )
            chatMessageRepository.save(chatMessage)
            logger.info("[PTMessage] ChatMessage saved for user ${schedule.user.id}, schedule $scheduleId")

            // 2. FCM 전송 및 NotificationHistory 저장 (nextTriggerAt 선갱신 포함)
            // bodyOverride로 동적 메시지 전달 — schedule.message 정적 템플릿은 DB에 그대로 보존
            val bodyOverride = if (effectiveMessage != schedule.message) effectiveMessage else null
            notificationService.sendScheduledNotificationWithFcm(schedule, bodyOverride)

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
        // "HH:mm" 형식 (예: "19:30") 우선 시도
        if (HH_MM_REGEX.matches(preferredTime)) {
            try {
                return LocalTime.parse(preferredTime)
            } catch (_: Exception) {
                // fallthrough to category mapping
            }
        }
        return when (preferredTime.lowercase()) {
            "morning" -> LocalTime.of(7, 0)
            "afternoon" -> LocalTime.of(14, 0)
            "evening" -> LocalTime.of(18, 0)
            else -> LocalTime.of(18, 0)
        }
    }

    companion object {
        private val HH_MM_REGEX = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")
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
