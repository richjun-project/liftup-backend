package com.richjun.liftupai.domain.notification.scheduler

import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import com.richjun.liftupai.global.time.AppTime

@Component
class PTMessageScheduler(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val ptScheduledMessageService: PTScheduledMessageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    fun processPTScheduledMessages() {
        val now = AppTime.utcNow()

        // 현재 시간에 보낼 PT 메시지 스케줄 찾기
        val schedules = notificationScheduleRepository.findDueSchedules(now)
            .filter { it.notificationType == com.richjun.liftupai.domain.notification.entity.NotificationType.AI_MESSAGE }
            .filter { it.enabled }

        if (schedules.isNotEmpty()) {
            logger.info("[PTScheduler] Processing ${schedules.size} PT scheduled messages at $now")
        }

        schedules.forEach { schedule ->
            try {
                // 각 스케줄을 독립 트랜잭션으로 처리 — 하나 실패해도 나머지에 영향 없음
                ptScheduledMessageService.sendScheduledPTMessage(schedule.id)
                logger.info("[PTScheduler] PT message sent for schedule ${schedule.id}, user ${schedule.user.id}")
            } catch (e: Exception) {
                logger.error("[PTScheduler] Failed to process PT schedule ${schedule.id}: ${e.message}", e)
            }
        }
    }
}
