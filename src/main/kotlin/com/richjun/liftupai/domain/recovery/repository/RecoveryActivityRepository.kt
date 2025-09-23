package com.richjun.liftupai.domain.recovery.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivity
import com.richjun.liftupai.domain.recovery.entity.RecoveryActivityType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface RecoveryActivityRepository : JpaRepository<RecoveryActivity, Long> {
    fun findByUserAndPerformedAtBetween(
        user: User,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<RecoveryActivity>

    fun findByUser_IdAndPerformedAtBetween(
        userId: Long,
        startDate: LocalDateTime,
        endDate: LocalDateTime
    ): List<RecoveryActivity>

    fun findByUserAndActivityType(
        user: User,
        activityType: RecoveryActivityType
    ): List<RecoveryActivity>

    @Query("""
        SELECT COUNT(ra) FROM RecoveryActivity ra
        WHERE ra.user.id = :userId
        AND ra.performedAt BETWEEN :startDate AND :endDate
    """)
    fun countActivitiesByUserAndDateRange(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): Long

    @Query("""
        SELECT ra.activityType, COUNT(ra) FROM RecoveryActivity ra
        WHERE ra.user.id = :userId
        AND ra.performedAt BETWEEN :startDate AND :endDate
        GROUP BY ra.activityType
        ORDER BY COUNT(ra) DESC
    """)
    fun findMostFrequentActivityType(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): List<Array<Any>>

    @Query("""
        SELECT AVG(ra.recoveryScore) FROM RecoveryActivity ra
        WHERE ra.user.id = :userId
        AND ra.performedAt BETWEEN :startDate AND :endDate
        AND ra.recoveryScore IS NOT NULL
    """)
    fun calculateAverageRecoveryScore(
        @Param("userId") userId: Long,
        @Param("startDate") startDate: LocalDateTime,
        @Param("endDate") endDate: LocalDateTime
    ): Double?
}