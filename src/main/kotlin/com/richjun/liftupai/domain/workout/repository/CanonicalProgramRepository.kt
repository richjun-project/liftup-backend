package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.workout.entity.CanonicalProgram
import com.richjun.liftupai.domain.workout.entity.WorkoutGoal
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CanonicalProgramRepository : JpaRepository<CanonicalProgram, Long> {
    fun findByCode(code: String): CanonicalProgram?

    fun findByTargetExperienceLevelAndTargetGoalAndIsActiveTrue(
        targetExperienceLevel: ExperienceLevel,
        targetGoal: WorkoutGoal
    ): List<CanonicalProgram>

    fun findByIsActiveTrue(): List<CanonicalProgram>
}
