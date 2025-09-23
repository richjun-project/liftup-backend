package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.Achievement
import com.richjun.liftupai.domain.workout.entity.AchievementType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface AchievementRepository : JpaRepository<Achievement, Long> {
    fun findByUser(user: User): List<Achievement>

    @Query("SELECT a FROM Achievement a WHERE a.user.id = :userId")
    fun findByUser_Id(userId: Long): List<Achievement>

    fun findByUserAndType(user: User, type: AchievementType): List<Achievement>

    fun existsByUserAndName(user: User, name: String): Boolean
}