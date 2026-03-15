package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.InjuryExerciseRestriction
import com.richjun.liftupai.domain.workout.entity.InjurySeverity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface InjuryExerciseRestrictionRepository : JpaRepository<InjuryExerciseRestriction, Long> {
    fun findByInjuryType(injuryType: String): List<InjuryExerciseRestriction>

    fun findByInjuryTypeAndSeverityIn(
        injuryType: String,
        severity: List<InjurySeverity>
    ): List<InjuryExerciseRestriction>
}
