package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.UserWorkoutPlan
import com.richjun.liftupai.domain.workout.entity.PlanStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserWorkoutPlanRepository : JpaRepository<UserWorkoutPlan, Long> {
    fun findFirstByUserIdAndStatusOrderByCreatedAtDesc(userId: Long, status: PlanStatus): UserWorkoutPlan?
    fun findByUserIdAndStatus(userId: Long, status: PlanStatus): List<UserWorkoutPlan>
    fun findByUserIdAndStatusIn(userId: Long, statuses: List<PlanStatus>): List<UserWorkoutPlan>
}
