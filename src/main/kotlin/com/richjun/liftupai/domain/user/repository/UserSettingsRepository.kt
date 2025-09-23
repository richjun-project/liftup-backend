package com.richjun.liftupai.domain.user.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.UserSettings
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserSettingsRepository : JpaRepository<UserSettings, Long> {
    fun findByUser(user: User): Optional<UserSettings>
    fun findByUser_Id(userId: Long): Optional<UserSettings>
}