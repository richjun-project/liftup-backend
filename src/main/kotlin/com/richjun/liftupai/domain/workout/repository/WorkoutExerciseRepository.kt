package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface WorkoutExerciseRepository : JpaRepository<WorkoutExercise, Long> {
    @Query("SELECT COUNT(es) FROM WorkoutExercise we JOIN we.sets es WHERE we.session.user.id = :userId AND we.exercise.id = :exerciseId")
    fun countSetsByUserAndExercise(userId: Long, exerciseId: Long): Int?

    @Query("SELECT AVG(es.weight) FROM WorkoutExercise we JOIN we.sets es WHERE we.session.user.id = :userId AND we.exercise.id = :exerciseId")
    fun calculateAverageWeight(userId: Long, exerciseId: Long): Double?

    fun findBySessionIdOrderByOrderInSession(sessionId: Long): List<WorkoutExercise>

    fun findBySessionIdAndExerciseId(sessionId: Long, exerciseId: Long): WorkoutExercise?
}