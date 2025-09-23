package com.richjun.liftupai.domain.auth.repository

import com.richjun.liftupai.domain.auth.entity.DeviceSession
import com.richjun.liftupai.domain.auth.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface DeviceSessionRepository : JpaRepository<DeviceSession, Long> {
    fun findByUserAndDeviceId(user: User, deviceId: String): Optional<DeviceSession>

    fun findByDeviceIdAndIsActive(deviceId: String, isActive: Boolean): Optional<DeviceSession>

    fun findAllByUser(user: User): List<DeviceSession>

    @Query("SELECT ds FROM DeviceSession ds WHERE ds.user.id = :userId AND ds.isActive = true")
    fun findActiveSessionsByUserId(userId: Long): List<DeviceSession>

    fun existsByDeviceIdAndIsActive(deviceId: String, isActive: Boolean): Boolean
}