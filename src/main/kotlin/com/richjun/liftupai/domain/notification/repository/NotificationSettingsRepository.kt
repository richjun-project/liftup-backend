package com.richjun.liftupai.domain.notification.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.entity.NotificationSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface NotificationSettingsRepository : JpaRepository<NotificationSettings, Long> {
    fun findByUser(user: User): Optional<NotificationSettings>

    fun findByUser_Id(userId: Long): Optional<NotificationSettings>
}