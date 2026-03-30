package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.UserPlanDay
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserPlanDayRepository : JpaRepository<UserPlanDay, Long> {
    fun findByPlanIdOrderByDayNumber(planId: Long): List<UserPlanDay>
    fun findByPlanIdAndDayNumber(planId: Long, dayNumber: Int): UserPlanDay?
}
