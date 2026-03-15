package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ExerciseSubstitution
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ExerciseSubstitutionRepository : JpaRepository<ExerciseSubstitution, Long> {
    @Query("SELECT es FROM ExerciseSubstitution es JOIN FETCH es.substituteExercise JOIN FETCH es.originalExercise WHERE es.originalExercise.id = :id ORDER BY es.priority")
    fun findByOriginalExerciseIdOrderByPriority(@Param("id") originalExerciseId: Long): List<ExerciseSubstitution>
}
