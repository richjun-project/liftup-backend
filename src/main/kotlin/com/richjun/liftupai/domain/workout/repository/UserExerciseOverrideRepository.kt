package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.UserExerciseOverride
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserExerciseOverrideRepository : JpaRepository<UserExerciseOverride, Long> {
    fun findByEnrollmentId(enrollmentId: Long): List<UserExerciseOverride>

    fun findByEnrollmentIdAndOriginalExerciseId(
        enrollmentId: Long,
        originalExerciseId: Long
    ): UserExerciseOverride?
}
