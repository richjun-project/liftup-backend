package com.richjun.liftupai.domain.notification.scheduler

import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import com.richjun.liftupai.global.time.AppTime

@Component
class PTMessageScheduler(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val ptScheduledMessageService: PTScheduledMessageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    @Transactional
    fun processPTScheduledMessages() {
        val now = AppTime.utcNow()

        // 현재 시간에 보낼 PT 메시지 스케줄 찾기
        val schedules = notificationScheduleRepository.findDueSchedules(now)
            .filter { it.notificationType == com.richjun.liftupai.domain.notification.entity.NotificationType.AI_MESSAGE }
            .filter { it.enabled }

        if (schedules.isNotEmpty()) {
            logger.info("Processing ${schedules.size} PT scheduled messages at $now")
        }

        schedules.forEach { schedule ->
            try {
                // PT 메시지 전송 (ChatMessage에 저장 + FCM 발송)
                ptScheduledMessageService.sendScheduledPTMessage(schedule)
                logger.info("PT message sent for user ${schedule.user.id}, next trigger at ${schedule.nextTriggerAt}")
            } catch (e: Exception) {
                logger.error("Failed to process PT schedule ${schedule.id}", e)
            }
        }
    }
}
