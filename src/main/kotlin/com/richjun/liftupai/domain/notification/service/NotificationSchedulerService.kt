package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.LocalDateTime

@Service
class NotificationSchedulerService(
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val notificationService: NotificationService
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * 매분마다 실행되어 발송해야 할 스케줄된 알림을 확인하고 전송합니다.
     */
    @Scheduled(fixedRate = 60000) // 1분마다 실행
    fun processScheduledNotifications() {
        try {
            val currentTime = LocalDateTime.now()
            val schedulesToTrigger = notificationScheduleRepository.findSchedulesToTrigger(currentTime)

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
     * 매일 자정에 실행되어 다음날의 알림 스케줄을 업데이트합니다.
     */
    @Scheduled(cron = "0 0 0 * * *") // 매일 자정
    fun updateNextTriggerTimes() {
        try {
            logger.info("Updating next trigger times for all active schedules")

            val allActiveSchedules = notificationScheduleRepository.findAll()
                .filter { it.enabled }

            allActiveSchedules.forEach { schedule ->
                val nextTrigger = calculateNextTrigger(schedule)
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

    private fun calculateNextTrigger(schedule: com.richjun.liftupai.domain.notification.entity.NotificationSchedule): LocalDateTime {
        val now = LocalDateTime.now()
        val today = now.dayOfWeek
        val todayTime = now.toLocalTime()

        // Convert our DayOfWeek enum to Java DayOfWeek
        val javaDays = schedule.days.map { day ->
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

        // If today is in the schedule and time hasn't passed, schedule for today
        if (javaDays.contains(today) && todayTime.isBefore(schedule.time)) {
            return now.toLocalDate().atTime(schedule.time)
        }

        // Find the next day in the schedule
        for (i in 1..7) {
            val nextDay = today.plus(i.toLong())
            if (javaDays.contains(nextDay)) {
                return now.toLocalDate().plusDays(i.toLong()).atTime(schedule.time)
            }
        }

        // This should never happen if days is not empty
        return now.plusDays(1).toLocalDate().atTime(schedule.time)
    }
}