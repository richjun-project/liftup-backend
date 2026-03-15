package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.InjuryExerciseRestriction
import com.richjun.liftupai.domain.workout.entity.InjurySeverity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface InjuryExerciseRestrictionRepository : JpaRepository<InjuryExerciseRestriction, Long> {
    @Query("SELECT r FROM InjuryExerciseRestriction r LEFT JOIN FETCH r.restrictedExercise LEFT JOIN FETCH r.suggestedSubstitute WHERE r.injuryType = :type")
    fun findByInjuryType(@Param("type") injuryType: String): List<InjuryExerciseRestriction>

    fun findByInjuryTypeAndSeverityIn(
        injuryType: String,
        severity: List<InjurySeverity>
    ): List<InjuryExerciseRestriction>
}
