package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.WorkoutExercise
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface WorkoutExerciseRepository : JpaRepository<WorkoutExercise, Long> {
    @Query("SELECT COUNT(es) FROM WorkoutExercise we JOIN we.sets es WHERE we.session.user.id = :userId AND we.exercise.id = :exerciseId")
    fun countSetsByUserAndExercise(userId: Long, exerciseId: Long): Int?

    @Query("SELECT AVG(es.weight) FROM WorkoutExercise we JOIN we.sets es WHERE we.session.user.id = :userId AND we.exercise.id = :exerciseId")
    fun calculateAverageWeight(userId: Long, exerciseId: Long): Double?

    fun findBySessionIdOrderByOrderInSession(sessionId: Long): List<WorkoutExercise>

    fun findBySessionIdAndExerciseId(sessionId: Long, exerciseId: Long): WorkoutExercise?

    @Query("SELECT we FROM WorkoutExercise we JOIN FETCH we.exercise WHERE we.session.id IN :sessionIds ORDER BY we.session.id, we.orderInSession")
    fun findBySessionIdInWithExercise(@Param("sessionIds") sessionIds: List<Long>): List<WorkoutExercise>

    /**
     * 사용자가 실제로 기록한(세트가 남은) 운동 id — 중복 없이 가장 최근에 한 순.
     * 취소·예정 세션은 제외한다. pageable 로 개수를 제한한다(예: PageRequest.of(0, 10)).
     */
    @Query("""
        SELECT we.exercise.id
        FROM WorkoutExercise we JOIN we.session s
        WHERE s.user.id = :userId
        AND s.status NOT IN (com.richjun.liftupai.domain.workout.entity.SessionStatus.CANCELLED,
                             com.richjun.liftupai.domain.workout.entity.SessionStatus.PLANNED)
        AND EXISTS (SELECT 1 FROM ExerciseSet es WHERE es.workoutExercise = we)
        GROUP BY we.exercise.id
        ORDER BY MAX(s.startTime) DESC, MAX(we.id) DESC
    """)
    fun findRecentExerciseIds(@Param("userId") userId: Long, pageable: Pageable): List<Long>
}
