package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface WorkoutSessionRepository : JpaRepository<WorkoutSession, Long> {
    fun findFirstByUserAndStatusOrderByStartTimeDesc(user: User, status: SessionStatus): Optional<WorkoutSession>

    fun findByUserOrderByStartTimeDesc(user: User, pageable: Pageable): Page<WorkoutSession>

    fun findByUserAndStartTimeBetweenOrderByStartTimeDesc(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime,
        pageable: Pageable
    ): Page<WorkoutSession>

    @Query("SELECT COUNT(w) FROM WorkoutSession w WHERE w.user = :user AND w.status = :status")
    fun countByUserAndStatus(user: User, status: SessionStatus): Long

    // Removed findByIdWithExercises - no longer needed after removing lazy loaded exercises

    @Query("SELECT SUM(w.duration) FROM WorkoutSession w WHERE w.user = :user AND w.startTime BETWEEN :startDate AND :endDate")
    fun getTotalDurationByUserAndPeriod(user: User, startDate: LocalDateTime, endDate: LocalDateTime): Int?

    @Query("SELECT SUM(w.totalVolume) FROM WorkoutSession w WHERE w.user = :user AND w.startTime BETWEEN :startDate AND :endDate")
    fun getTotalVolumeByUserAndPeriod(user: User, startDate: LocalDateTime, endDate: LocalDateTime): Double?

    fun findTopByUserOrderByStartTimeDesc(user: User): WorkoutSession?

    fun findTop7ByUserOrderByStartTimeDesc(user: User): List<WorkoutSession>

    @Query("SELECT COUNT(DISTINCT DATE(w.startTime)) FROM WorkoutSession w WHERE w.user = :user AND w.status = 'COMPLETED'")
    fun countDistinctWorkoutDays(user: User): Int

    @Query("SELECT EXISTS(SELECT 1 FROM WorkoutSession w WHERE w.user = :user AND DATE(w.startTime) = :date AND w.status = 'COMPLETED')")
    fun existsByUserAndDate(user: User, date: java.time.LocalDate): Boolean

    @Query("SELECT COUNT(w) FROM WorkoutSession w WHERE w.user = :user AND w.startTime BETWEEN :startDate AND :endDate AND w.status = 'COMPLETED'")
    fun countByUserAndDateRange(user: User, startDate: LocalDateTime, endDate: LocalDateTime): Int

    fun findByUserAndStartTimeBetween(user: User, startDate: LocalDateTime, endDate: LocalDateTime): List<WorkoutSession>

    fun findByUser_IdAndStartTimeBetween(userId: Long, startDate: LocalDateTime, endDate: LocalDateTime): List<WorkoutSession>

    fun findAllByUserAndStatus(user: User, status: SessionStatus): List<WorkoutSession>

    fun findTop10ByUserAndStatusInOrderByStartTimeDesc(
        user: User,
        status: List<SessionStatus>
    ): List<WorkoutSession>

    fun findFirstByUserOrderByStartTimeDesc(user: User): Optional<WorkoutSession>

    fun countByUserAndStartTimeAfter(user: User, startTime: LocalDateTime): Long
}