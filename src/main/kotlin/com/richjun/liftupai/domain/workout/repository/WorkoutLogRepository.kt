package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.WorkoutLog
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface WorkoutLogRepository : JpaRepository<WorkoutLog, Long> {
    fun findBySession(session: WorkoutSession): List<WorkoutLog>
    fun findBySessionOrderByTimestamp(session: WorkoutSession): List<WorkoutLog>
    fun findBySessionId(sessionId: Long): List<WorkoutLog>
}