package com.richjun.liftupai.domain.notification.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.entity.NotificationHistory
import com.richjun.liftupai.domain.notification.entity.NotificationType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface NotificationHistoryRepository : JpaRepository<NotificationHistory, Long> {
    fun findByUser(user: User, pageable: Pageable): Page<NotificationHistory>

    fun findByUserAndIsRead(user: User, isRead: Boolean, pageable: Pageable): Page<NotificationHistory>

    fun findByUserAndType(user: User, type: NotificationType, pageable: Pageable): Page<NotificationHistory>

    fun findByNotificationId(notificationId: String): Optional<NotificationHistory>

    fun findByUserAndNotificationId(user: User, notificationId: String): Optional<NotificationHistory>

    @Query("SELECT COUNT(nh) FROM NotificationHistory nh WHERE nh.user = :user AND nh.isRead = false")
    fun countUnreadByUser(user: User): Long

    @Query("SELECT nh FROM NotificationHistory nh WHERE nh.user = :user ORDER BY nh.createdAt DESC")
    fun findByUserOrderByCreatedAtDesc(user: User, pageable: Pageable): Page<NotificationHistory>

    @Modifying
    @Query("UPDATE NotificationHistory nh SET nh.schedule = null WHERE nh.schedule.id IN :scheduleIds")
    fun clearScheduleReferences(@Param("scheduleIds") scheduleIds: List<Long>)
}