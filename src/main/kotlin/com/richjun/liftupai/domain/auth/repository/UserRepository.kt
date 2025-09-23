package com.richjun.liftupai.domain.auth.repository

import com.richjun.liftupai.domain.auth.entity.User
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): Optional<User>

    fun findByNickname(nickname: String): Optional<User>

    fun existsByEmail(email: String): Boolean

    fun existsByNickname(nickname: String): Boolean

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.profile WHERE u.email = :email")
    fun findByEmailWithProfile(email: String): Optional<User>

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.profile WHERE u.id = :id")
    fun findByIdWithProfile(id: Long): Optional<User>

    fun findByRefreshToken(refreshToken: String): Optional<User>

    fun findByDeviceId(deviceId: String): Optional<User>

    @Query("SELECT u FROM User u LEFT JOIN FETCH u.profile WHERE u.deviceId = :deviceId")
    fun findByDeviceIdWithProfile(deviceId: String): Optional<User>

    fun existsByDeviceId(deviceId: String): Boolean
}