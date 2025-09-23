package com.richjun.liftupai.domain.notification.scheduler

import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.notification.service.PTScheduledMessageService
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

@Component
class PTMessageScheduler(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val ptScheduledMessageService: PTScheduledMessageService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60000) // 1분마다 실행
    @Transactional
    fun processPTScheduledMessages() {
        val now = LocalDateTime.now()

        // 현재 시간에 보낼 PT 메시지 스케줄 찾기
        val schedules = notificationScheduleRepository.findDueSchedules(now)
            .filter { it.notificationType == NotificationType.AI_MESSAGE }
            .filter { it.enabled }

        if (schedules.isNotEmpty()) {
            logger.info("Processing ${schedules.size} PT scheduled messages at $now")
        }

        schedules.forEach { schedule ->
            try {
                // PT 메시지 전송 (ChatMessage에 저장 + FCM 발송)
                ptScheduledMessageService.sendScheduledPTMessage(schedule)

                // 다음 트리거 시간 계산 및 업데이트
                val nextTrigger = calculateNextTrigger(schedule.days, schedule.time)
                schedule.nextTriggerAt = nextTrigger
                notificationScheduleRepository.save(schedule)

                logger.info("PT message sent for user ${schedule.user.id}, next trigger at $nextTrigger")
            } catch (e: Exception) {
                logger.error("Failed to process PT schedule ${schedule.id}", e)
            }
        }
    }

    private fun calculateNextTrigger(
        days: Set<com.richjun.liftupai.domain.notification.entity.DayOfWeek>,
        time: java.time.LocalTime
    ): LocalDateTime {
        val now = LocalDateTime.now()
        val today = now.dayOfWeek
        val todayTime = now.toLocalTime()

        // Java DayOfWeek로 변환
        val javaDays = days.map { day ->
            when (day) {
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.MON -> java.time.DayOfWeek.MONDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.TUE -> java.time.DayOfWeek.TUESDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.WED -> java.time.DayOfWeek.WEDNESDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.THU -> java.time.DayOfWeek.THURSDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.FRI -> java.time.DayOfWeek.FRIDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.SAT -> java.time.DayOfWeek.SATURDAY
                com.richjun.liftupai.domain.notification.entity.DayOfWeek.SUN -> java.time.DayOfWeek.SUNDAY
            }
        }.toSet()

        // 오늘이 스케줄에 있고 시간이 아직 안 지났으면 오늘 설정
        if (javaDays.contains(today) && todayTime.isBefore(time)) {
            return now.toLocalDate().atTime(time)
        }

        // 다음 날 찾기
        for (i in 1..7) {
            val nextDay = today.plus(i.toLong())
            if (javaDays.contains(nextDay)) {
                return now.toLocalDate().plusDays(i.toLong()).atTime(time)
            }
        }

        return now.plusDays(1).toLocalDate().atTime(time)
    }
}