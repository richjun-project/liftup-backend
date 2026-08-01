package com.richjun.liftupai.domain.notification.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.entity.DevicePlatform
import com.richjun.liftupai.domain.notification.entity.NotificationDevice
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface NotificationDeviceRepository : JpaRepository<NotificationDevice, Long> {
    fun findByDeviceToken(deviceToken: String): Optional<NotificationDevice>

    fun findByUser(user: User): List<NotificationDevice>

    fun findByUserAndIsActive(user: User, isActive: Boolean): List<NotificationDevice>

    fun findByUserAndPlatform(user: User, platform: DevicePlatform): List<NotificationDevice>

    // 최근 사용 순 정렬 — 알림은 "가장 최근 쓴 기기 1대"에만 보내므로 순서가 중요하다.
    @Query("SELECT nd FROM NotificationDevice nd WHERE nd.user = :user AND nd.isActive = true ORDER BY nd.lastUsedAt DESC")
    fun findActiveDevicesByUser(user: User): List<NotificationDevice>

    @Query("SELECT nd FROM NotificationDevice nd WHERE nd.lastUsedAt < :date AND nd.isActive = true")
    fun findInactiveDevices(date: LocalDateTime): List<NotificationDevice>

    fun deleteByDeviceToken(deviceToken: String)
}