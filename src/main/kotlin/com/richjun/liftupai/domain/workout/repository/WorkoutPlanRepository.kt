package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.WorkoutPlan
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.Optional

@Repository
interface WorkoutPlanRepository : JpaRepository<WorkoutPlan, Long> {
    fun findByUser_Id(userId: Long): List<WorkoutPlan>
    fun findByUserAndName(user: User, name: String): Optional<WorkoutPlan>
    fun findFirstByUser_IdOrderByCreatedAtDesc(userId: Long): Optional<WorkoutPlan>
}