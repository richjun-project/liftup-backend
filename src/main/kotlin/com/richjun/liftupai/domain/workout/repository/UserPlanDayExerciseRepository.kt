package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.UserPlanDayExercise
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserPlanDayExerciseRepository : JpaRepository<UserPlanDayExercise, Long> {
    fun findByPlanDayIdOrderByOrderInDay(planDayId: Long): List<UserPlanDayExercise>

    @Query("SELECT e FROM UserPlanDayExercise e JOIN FETCH e.exercise WHERE e.planDay.id = :planDayId ORDER BY e.orderInDay")
    fun findByPlanDayIdWithExerciseOrderByOrderInDay(@Param("planDayId") planDayId: Long): List<UserPlanDayExercise>
}
