package com.richjun.liftupai.domain.notification.scheduler

import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.repository.NotificationSettingsRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import com.richjun.liftupai.global.time.AppTime

@Component
class PTMessageScheduler(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationSettingsRepository: NotificationSettingsRepository,
    private val ptScheduledMessageService: PTScheduledMessageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000)
    fun processPTScheduledMessages() {
        val now = AppTime.utcNow()

        val scheduleIds = notificationScheduleRepository.findDueSchedules(now)
            .filter { it.notificationType == NotificationType.AI_MESSAGE }
            .filter { it.enabled }
            .filter { schedule ->
                // 유저의 aiMessages 토글이 켜져 있는지 확인
                val settings = notificationSettingsRepository.findByUser(schedule.user).orElse(null)
                val allowed = settings?.aiMessages ?: true
                if (!allowed) {
                    logger.info("[PTScheduler] AI messages disabled for user ${schedule.user.id}, skipping schedule ${schedule.id}")
                }
                allowed
            }
            .map { it.id }

        if (scheduleIds.isNotEmpty()) {
            logger.info("[PTScheduler] Processing ${scheduleIds.size} PT scheduled messages at $now")
        }

        scheduleIds.forEach { scheduleId ->
            try {
                ptScheduledMessageService.sendScheduledPTMessage(scheduleId)
                logger.info("[PTScheduler] PT message sent for schedule $scheduleId")
            } catch (e: Exception) {
                logger.error("[PTScheduler] Failed to process PT schedule $scheduleId: ${e.message}", e)
            }
        }
    }
}
