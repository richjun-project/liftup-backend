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

    @Query("SELECT nd FROM NotificationDevice nd WHERE nd.user = :user AND nd.isActive = true")
    fun findActiveDevicesByUser(user: User): List<NotificationDevice>

    @Query("SELECT nd FROM NotificationDevice nd WHERE nd.lastUsedAt < :date AND nd.isActive = true")
    fun findInactiveDevices(date: LocalDateTime): List<NotificationDevice>

    fun deleteByDeviceToken(deviceToken: String)
}