package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ExerciseSetRepository : JpaRepository<ExerciseSet, Long> {
    fun findByWorkoutExerciseId(workoutExerciseId: Long): List<ExerciseSet>

    @Query("""
        SELECT es FROM ExerciseSet es
        JOIN es.workoutExercise we
        JOIN we.session ws
        WHERE ws.user.id = :userId
        AND we.exercise.id = :exerciseId
        AND ws.startTime >= CURRENT_TIMESTAMP - :days DAY
        ORDER BY ws.startTime DESC
    """)
    fun findRecentByUserAndExercise(
        @Param("userId") userId: Long,
        @Param("exerciseId") exerciseId: Long,
        @Param("days") days: Int
    ): List<ExerciseSet>

    @Query("""
        SELECT es FROM ExerciseSet es
        JOIN es.workoutExercise we
        JOIN we.session ws
        WHERE ws.user.id = :userId
        AND we.exercise.id = :exerciseId
        AND ws.startTime >= :since
        AND ws.status = 'COMPLETED'
        ORDER BY ws.startTime DESC
    """)
    fun findCompletedSetsByUserAndExercise(
        @Param("userId") userId: Long,
        @Param("exerciseId") exerciseId: Long,
        @Param("since") since: LocalDateTime
    ): List<ExerciseSet>
}