package com.richjun.liftupai.domain.notification.service

import com.richjun.liftupai.domain.notification.entity.NotificationType
import com.richjun.liftupai.domain.notification.repository.NotificationScheduleRepository
import com.richjun.liftupai.domain.user.repository.UserProfileRepository
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 온보딩을 완료(프로필 보유)했지만 PT 체크인 스케줄이 없는 기존 사용자에게 스케줄을 백필한다.
 * PT 스케줄 자동 생성이 도입되기 전 가입자들은 체크인 메시지를 전혀 받지 못했다(2026-08 실측: 프로필 9명 중 3명만 보유).
 * 매 기동마다 실행되지만 스케줄이 이미 있는 사용자는 건너뛰므로 멱등하다.
 */
@Component
class PtScheduleBackfillRunner(
    private val userProfileRepository: UserProfileRepository,
    private val notificationScheduleRepository: NotificationScheduleRepository,
    private val ptScheduledMessageService: PTScheduledMessageService
) : ApplicationRunner {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments?) {
        var created = 0
        userProfileRepository.findAll().forEach { profile ->
            val user = profile.user
            val hasPtSchedule = notificationScheduleRepository.findByUser(user)
                .any { it.notificationType == NotificationType.AI_MESSAGE }
            if (!hasPtSchedule) {
                try {
                    ptScheduledMessageService.createPTSchedulesForUser(user.id)
                    created++
                } catch (e: Exception) {
                    logger.warn("PT schedule backfill failed for user ${user.id}: ${e.message}")
                }
            }
        }
        if (created > 0) {
            logger.info("PT schedule backfill: created schedules for $created users")
        }
    }
}
