package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ExerciseSubstitution
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExerciseSubstitutionRepository : JpaRepository<ExerciseSubstitution, Long> {
    fun findByOriginalExerciseIdOrderByPriority(originalExerciseId: Long): List<ExerciseSubstitution>
}
