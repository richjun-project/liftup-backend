package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.auth.repository.UserRepository
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.chat.entity.ChatMessage
import com.richjun.liftupai.domain.chat.entity.MessageStatus
import com.richjun.liftupai.domain.chat.entity.MessageType
import com.richjun.liftupai.domain.chat.repository.ChatMessageRepository
import com.richjun.liftupai.domain.notification.entity.*
import com.richjun.liftupai.domain.notification.util.NotificationLocalization
import com.richjun.liftupai.domain.notification.util.NotificationScheduleTimeCalculator
import com.richjun.liftupai.domain.notification.util.PtMessagePolicy
import com.richjun.liftupai.domain.notification.repository.NotificationHistoryRepository
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.user.entity.PTStyle
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import com.richjun.liftupai.domain.user.repository.UserSettingsRepository
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.workout.repository.WorkoutSessionRepository
import com.richjun.liftupai.global.exception.ResourceNotFoundException
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
     * 호출자 트랜잭션과 분리해서 재생성한다. 여기서 실패해도 호출자의 커밋(예: 언어 설정 저장)이
     * rollback-only 로 오염되지 않는다.
     */
    @org.springframework.transaction.annotation.Transactional(
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW
    )
    fun recreatePTSchedulesInNewTransaction(userId: Long) = createPTSchedulesForUser(userId)

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

        val preferredWorkoutTime = parseWorkoutTime(
            settings?.preferredWorkoutTime ?: profile.preferredWorkoutTime ?: "evening"
        )

        logger.info("Creating PT schedules for user ${user.id}")

        // 기존 스케줄 삭제
        deleteExistingPTSchedules(user)

        // 1. 아침 식사 체크 (07:30)
        createSchedule(
            user = user,
            scheduleName = "morning_meal_check",
            time = LocalTime.of(7, 30),
            message = ptMessageTemplates.getMorningMealMessage(locale),
            days = getAllDays()
        )

        // 2. 점심 식사 체크 (12:30)
        createSchedule(
            user = user,
            scheduleName = "lunch_meal_check",
            time = LocalTime.of(12, 30),
            message = ptMessageTemplates.getLunchMealMessage(locale),
            days = getAllDays()
        )

        // 3. 운동 30분 전 알림
        val workoutReminderTime = preferredWorkoutTime.minusMinutes(30)
        createSchedule(
            user = user,
            scheduleName = "workout_reminder",
            time = workoutReminderTime,
            message = ptMessageTemplates.getWorkoutReminderMessage(locale),
            days = getAllDays()
        )

        // 4. 저녁 식사 관리 (19:00)
        createSchedule(
            user = user,
            scheduleName = "dinner_meal_check",
            time = LocalTime.of(19, 0),
            message = ptMessageTemplates.getDinnerMealMessage(locale),
            days = getAllDays()
        )

        // 5. 수면 준비 메시지 (22:00)
        createSchedule(
            user = user,
            scheduleName = "sleep_prep",
            time = LocalTime.of(22, 0),
            message = ptMessageTemplates.getSleepPrepMessage(locale),
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

        val daysInactive = daysSinceLastActivity(schedule.user, now)
        if (!PtMessagePolicy.shouldSend(schedule.scheduleName, daysInactive)) {
            schedule.nextTriggerAt = notificationService.recalculateNextTrigger(schedule)
            notificationScheduleRepository.save(schedule)
            logger.debug("[PTMessage] Skipped schedule $scheduleId (${schedule.scheduleName}) for user ${schedule.user.id}, inactive ${daysInactive}d")
            return
        }

        try {
            // 발송 시점에 템플릿을 다시 읽는다.
            // schedule.message는 스케줄 생성 시점의 스냅샷이라, 템플릿 문구를 고쳐도
            // 이미 생성된 행은 옛 문구를 그대로 보낸다(2026-04 생성 행이 9월까지 옛 문구 발송).
            val body = resolveTemplateMessage(schedule)

            // 1. ChatMessage에 저장 (채팅 히스토리에 표시)
            val actions = actionsFor(schedule.scheduleName)
            val chatMessage = ChatMessage(
                user = schedule.user,
                userMessage = "",
                aiResponse = body,
                messageType = MessageType.SYSTEM,
                status = MessageStatus.COMPLETED,
                actions = ChatAction.join(actions)
            )
            chatMessageRepository.save(chatMessage)
            logger.info("[PTMessage] ChatMessage saved for user ${schedule.user.id}, schedule $scheduleId")

            // 2. 저장된 스냅샷이 낡았으면 갱신해 두 번 다시 옛 문구가 나가지 않게 한다
            if (schedule.message != body) {
                logger.info("[PTMessage] Stale snapshot replaced for schedule $scheduleId")
                schedule.message = body
            }

            // 3. FCM 전송 및 NotificationHistory 저장 (nextTriggerAt 선갱신 포함)
            notificationService.sendScheduledNotificationWithFcm(
                schedule, body,
                extraData = actions.firstOrNull()?.let { mapOf("action" to it.name) } ?: emptyMap()
            )

            logger.info("[PTMessage] PT message sent and saved to chat for user ${schedule.user.id}")
        } catch (e: Exception) {
            logger.error("[PTMessage] Failed to send PT message for schedule ${schedule.id}: ${e.message}", e)
        }
    }

    /**
     * 마지막 활동(로그인·토큰 갱신) 이후 지난 일수. 로그인 기록이 없으면 가입일 기준.
     * 액세스 토큰이 1시간짜리라 앱을 열 때마다 리프레시가 일어나고, 리프레시가 lastLoginAt 을 갱신한다.
     */
    private fun daysSinceLastActivity(user: User, now: java.time.LocalDateTime): Long {
        val lastActivity = user.lastLoginAt ?: user.joinDate
        return java.time.Duration.between(lastActivity, now).toDays().coerceAtLeast(0)
    }

    /**
     * scheduleName으로 현재 템플릿 문구를 다시 해석한다.
     * 알려진 PT 스케줄이 아니면(사용자 정의 스케줄 등) 저장된 message를 그대로 쓴다.
     */
    private fun resolveTemplateMessage(schedule: NotificationSchedule): String {
        val locale = resolveLocale(schedule.user.id)
        // 사용자가 고른 트레이너 말투로 보낸다(예전엔 모두 기본 문구였다).
        val style = userProfileRepository.findByUser(schedule.user).orElse(null)?.ptStyle?.name
        return when (schedule.scheduleName) {
            "morning_meal_check" -> ptMessageTemplates.getMorningMealMessage(locale, style)
            "lunch_meal_check" -> ptMessageTemplates.getLunchMealMessage(locale, style)
            "workout_reminder" ->
                // 2026-09 실측: 최근 30일 실가입 23명 중 세트를 하나라도 완료한 사람이 0명이었다.
                // 첫 운동 전인 사용자에게는 "한 세트만" 으로 문턱을 낮춘 문구를 보낸다.
                if (workoutSessionRepository.countByUserAndStatus(schedule.user, SessionStatus.COMPLETED) == 0L) {
                    ptMessageTemplates.getFirstWorkoutMessage(locale, style)
                } else {
                    ptMessageTemplates.getWorkoutReminderMessage(locale, style)
                }
            "dinner_meal_check" -> ptMessageTemplates.getDinnerMealMessage(locale, style)
            "sleep_prep" -> ptMessageTemplates.getSleepPrepMessage(locale, style)
            else -> schedule.message
        }
    }

    /** 알림·채팅 메시지 아래 버튼: 운동 알림은 오늘 운동, 식사 체크는 식단 기록으로 바로 간다. */
    private fun actionsFor(scheduleName: String): List<ChatAction> = when (scheduleName) {
        "workout_reminder" -> listOf(ChatAction.START_TODAY_WORKOUT)
        "morning_meal_check", "lunch_meal_check", "dinner_meal_check" -> listOf(ChatAction.OPEN_MEAL_LOG)
        else -> emptyList()
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
            // 삭제는 flush 때 나가는데 새 일정 INSERT(IDENTITY)는 즉시 나간다. 호출자가 곧바로
            // 영속성 컨텍스트를 비우면(프로필 저장의 entityManager.clear) 삭제만 사라져 옛 일정이 남았다(2026-10).
            notificationScheduleRepository.flush()
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
