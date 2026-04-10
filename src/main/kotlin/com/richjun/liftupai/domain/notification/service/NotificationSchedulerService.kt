package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.notification.entity.NotificationSchedule
import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.global.time.AppTime
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

@Service
class NotificationSchedulerService(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 매분마다 실행되어 발송해야 할 스케줄된 알림을 확인하고 전송합니다.
     * fixedDelay 사용: 이전 실행이 끝난 후 1분 대기 (fixedRate의 중첩 실행 방지)
     */
    @Scheduled(fixedDelay = 60000) // 이전 실행 완료 후 1분
    fun processScheduledNotifications() {
        try {
            val currentTime = AppTime.utcNow()
            // AI_MESSAGE는 PTMessageScheduler가 처리하므로 제외
            // 유저별 알림 설정 토글도 확인
            val schedulesToTrigger = notificationScheduleRepository.findSchedulesToTrigger(currentTime)
                .filter { it.notificationType != NotificationType.AI_MESSAGE }
                .filter { isNotificationTypeAllowed(it) }

            if (schedulesToTrigger.isNotEmpty()) {
                logger.info("Found ${schedulesToTrigger.size} notifications to send at $currentTime")

                schedulesToTrigger.forEach { schedule ->
                    try {
                        notificationService.sendScheduledNotification(schedule)
                        logger.info("Sent scheduled notification: ${schedule.scheduleName} for user ${schedule.user.id}")
                    } catch (e: Exception) {
                        logger.error("Failed to send scheduled notification: ${schedule.id}", e)
                    }
                }
            }
        } catch (e: Exception) {
            logger.error("Error processing scheduled notifications", e)
        }
    }

    /**
     * 4시간마다 실행하여 모든 타임존의 알림 스케줄을 업데이트합니다.
     * UTC 자정 1회가 아닌 주기적 실행으로 글로벌 타임존을 지원합니다.
     */
    @Scheduled(fixedRate = 14400000) // 4시간마다 (4 * 60 * 60 * 1000)
    fun updateNextTriggerTimes() {
        try {
            logger.info("Updating next trigger times for all active schedules")

            val allActiveSchedules = notificationScheduleRepository.findAll()
                .filter { it.enabled }

            allActiveSchedules.forEach { schedule ->
                val nextTrigger = notificationService.recalculateNextTrigger(schedule)
                if (schedule.nextTriggerAt != nextTrigger) {
                    schedule.nextTriggerAt = nextTrigger
                    notificationScheduleRepository.save(schedule)
                }
            }

            logger.info("Updated ${allActiveSchedules.size} notification schedules")
        } catch (e: Exception) {
            logger.error("Error updating next trigger times", e)
        }
    }

    /**
     * 유저의 NotificationSettings 토글에 따라 해당 알림 타입이 허용되는지 확인
     */
    private fun isNotificationTypeAllowed(schedule: NotificationSchedule): Boolean {
        val settings = notificationSettingsRepository.findByUser(schedule.user).orElse(null)
            ?: return true // 설정 없으면 기본 허용

        val allowed = when (schedule.notificationType) {
            NotificationType.WORKOUT_REMINDER -> settings.workoutReminder
            NotificationType.ACHIEVEMENT -> settings.achievements
            NotificationType.RECOVERY_ALERT -> settings.recoveryAlerts
            NotificationType.AI_MESSAGE -> settings.aiMessages
            NotificationType.STREAK, NotificationType.REST_DAY -> true // 항상 허용
        }

        if (!allowed) {
            logger.info("[Scheduler] ${schedule.notificationType} disabled for user ${schedule.user.id}, skipping schedule ${schedule.id}")
        }
        return allowed
    }
}
