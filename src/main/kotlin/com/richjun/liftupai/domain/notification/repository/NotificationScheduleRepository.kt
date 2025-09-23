package com.richjun.liftupai.domain.notification.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.entity.NotificationSchedule
import com.richjun.liftupai.domain.notification.entity.NotificationType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.time.LocalTime

@Repository
interface NotificationScheduleRepository : JpaRepository<NotificationSchedule, Long> {
    fun findByUser(user: User): List<NotificationSchedule>

    fun findByUserAndEnabled(user: User, enabled: Boolean): List<NotificationSchedule>

    fun findByUserAndNotificationType(user: User, notificationType: NotificationType): List<NotificationSchedule>

    @Query("SELECT ns FROM NotificationSchedule ns WHERE ns.enabled = true AND ns.nextTriggerAt <= :currentTime")
    fun findSchedulesToTrigger(currentTime: LocalDateTime): List<NotificationSchedule>

    @Query("SELECT ns FROM NotificationSchedule ns WHERE ns.enabled = true AND ns.nextTriggerAt <= :currentTime")
    fun findDueSchedules(currentTime: LocalDateTime): List<NotificationSchedule>

    @Query("SELECT ns FROM NotificationSchedule ns WHERE ns.user = :user AND ns.id = :scheduleId")
    fun findByUserAndId(user: User, scheduleId: Long): NotificationSchedule?
}