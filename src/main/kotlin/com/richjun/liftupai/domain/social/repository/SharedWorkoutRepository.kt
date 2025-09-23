package com.richjun.liftupai.domain.social.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.social.entity.SharedWorkout
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface SharedWorkoutRepository : JpaRepository<SharedWorkout, Long> {
    fun findByShareId(shareId: String): Optional<SharedWorkout>
    fun findByUserOrderByCreatedAtDesc(user: User): List<SharedWorkout>
    fun findByVisibilityOrderByCreatedAtDesc(visibility: String): List<SharedWorkout>
}