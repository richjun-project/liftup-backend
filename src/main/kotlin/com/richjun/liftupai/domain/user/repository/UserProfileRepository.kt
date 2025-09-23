package com.richjun.liftupai.domain.user.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface UserProfileRepository : JpaRepository<UserProfile, Long> {
    fun findByUser(user: User): Optional<UserProfile>
    fun findByUser_Id(userId: Long): Optional<UserProfile>
}