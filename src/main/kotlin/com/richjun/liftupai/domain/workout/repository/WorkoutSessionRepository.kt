package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.SessionStatus
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.WorkoutSession
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface WorkoutSessionRepository : JpaRepository<WorkoutSession, Long> {
    fun findFirstByUserAndStatusOrderByStartTimeDesc(user: User, status: SessionStatus): Optional<WorkoutSession>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ws FROM WorkoutSession ws WHERE ws.user = :user AND ws.status = :status ORDER BY ws.startTime DESC LIMIT 1")
    fun findFirstByUserAndStatusWithLock(@Param("user") user: User, @Param("status") status: SessionStatus): Optional<WorkoutSession>

    /**
     * 같은 세션의 업데이트·완료 요청을 차례로 처리하려고 세션 행을 잠근다. 트랜잭션의 **첫 읽기**로 불러야 한다 —
     * 앞에서 다른 행을 먼저 읽으면 그때 잡힌 스냅샷으로 세트를 읽어, 앞 요청이 지운 세트를 다시 지우다 실패한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT ws FROM WorkoutSession ws WHERE ws.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Optional<WorkoutSession>

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

    @Query("SELECT COUNT(DISTINCT DATE(w.startTime)) FROM WorkoutSession w WHERE w.user = :user AND w.startTime BETWEEN :startDate AND :endDate AND w.status = 'COMPLETED'")
    fun countByUserAndDateRange(user: User, startDate: LocalDateTime, endDate: LocalDateTime): Int

    fun findByUserAndStartTimeBetween(user: User, startDate: LocalDateTime, endDate: LocalDateTime): List<WorkoutSession>

    fun findByUser_IdAndStartTimeBetween(userId: Long, startDate: LocalDateTime, endDate: LocalDateTime): List<WorkoutSession>

    fun findAllByUserAndStatus(user: User, status: SessionStatus): List<WorkoutSession>

    fun findTop10ByUserAndStatusInOrderByStartTimeDesc(
        user: User,
        status: List<SessionStatus>
    ): List<WorkoutSession>

    @Query("SELECT ws FROM WorkoutSession ws WHERE ws.user = :user AND ws.status IN :statuses ORDER BY ws.startTime DESC")
    fun findByUserAndStatusInOrderByStartTimeDesc(
        @Param("user") user: User,
        @Param("statuses") statuses: List<SessionStatus>,
        pageable: Pageable
    ): List<WorkoutSession>

    fun findFirstByUserOrderByStartTimeDesc(user: User): Optional<WorkoutSession>

    fun countByUserAndStartTimeAfter(user: User, startTime: LocalDateTime): Long

    fun findByUserAndStartTimeAfter(user: User, startTime: LocalDateTime): List<WorkoutSession>

    /** 방치된 진행 중 세션 정리용 */
    fun findAllByStatusAndStartTimeBefore(status: SessionStatus, before: LocalDateTime): List<WorkoutSession>
}
