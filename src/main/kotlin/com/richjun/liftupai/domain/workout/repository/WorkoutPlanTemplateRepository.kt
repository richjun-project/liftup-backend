package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.WorkoutPlanTemplate
import com.richjun.liftupai.domain.workout.entity.PlanSourceType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WorkoutPlanTemplateRepository : JpaRepository<WorkoutPlanTemplate, Long> {
    fun findByCode(code: String): WorkoutPlanTemplate?
    fun findByIsActiveTrueAndSourceTypeOrderBySortOrder(sourceType: PlanSourceType): List<WorkoutPlanTemplate>
    fun findByOwnerUserIdAndSourceType(userId: Long, sourceType: PlanSourceType): List<WorkoutPlanTemplate>
    fun findByIsActiveTrueAndSourceTypeAndIsPremiumFalseOrderBySortOrder(sourceType: PlanSourceType): List<WorkoutPlanTemplate>
}
