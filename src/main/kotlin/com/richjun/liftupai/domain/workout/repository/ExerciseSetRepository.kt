package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.ExerciseSet
import com.richjun.liftupai.domain.workout.entity.SessionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ExerciseSetRepository : JpaRepository<ExerciseSet, Long> {
    fun findByWorkoutExerciseId(workoutExerciseId: Long): List<ExerciseSet>

    @Query("SELECT es FROM ExerciseSet es WHERE es.workoutExercise.id IN :workoutExerciseIds")
    fun findByWorkoutExerciseIdIn(@Param("workoutExerciseIds") workoutExerciseIds: List<Long>): List<ExerciseSet>

    @Query("""
        SELECT es FROM ExerciseSet es
        JOIN es.workoutExercise we
        JOIN we.session ws
        WHERE ws.user.id = :userId
        AND we.exercise.id = :exerciseId
        AND ws.startTime >= :since
        ORDER BY ws.startTime DESC
    """)
    fun findRecentByUserAndExercise(
        @Param("userId") userId: Long,
        @Param("exerciseId") exerciseId: Long,
        @Param("since") since: LocalDateTime
    ): List<ExerciseSet>

    @Query("""
        SELECT es FROM ExerciseSet es
        JOIN es.workoutExercise we
        JOIN we.session ws
        WHERE ws.user.id = :userId
        AND we.exercise.id = :exerciseId
        AND ws.startTime >= :since
        AND ws.status = :status
        ORDER BY ws.startTime DESC
    """)
    fun findCompletedSetsByUserAndExercise(
        @Param("userId") userId: Long,
        @Param("exerciseId") exerciseId: Long,
        @Param("since") since: LocalDateTime,
        @Param("status") status: SessionStatus = SessionStatus.COMPLETED
    ): List<ExerciseSet>
}