package com.richjun.liftupai.domain.recovery.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.recovery.entity.MuscleRecovery
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository
import java.time.LocalDateTime
import java.util.Optional

@Repository
interface MuscleRecoveryRepository : JpaRepository<MuscleRecovery, Long> {
    fun findByUser(user: User): List<MuscleRecovery>

    fun findByUserAndMuscleGroup(user: User, muscleGroup: String): Optional<MuscleRecovery>

    fun findByUserOrderByLastWorkedDesc(user: User): List<MuscleRecovery>

    @Query("SELECT m FROM MuscleRecovery m WHERE m.user = :user AND m.recoveryPercentage < 100")
    fun findRecoveringMuscles(user: User): List<MuscleRecovery>

    @Query("SELECT m FROM MuscleRecovery m WHERE m.user = :user AND m.recoveryPercentage >= :threshold")
    fun findReadyMuscles(user: User, threshold: Int = 80): List<MuscleRecovery>

    fun findByUserAndLastWorkedBefore(user: User, date: LocalDateTime): List<MuscleRecovery>

    fun findByUser_Id(userId: Long): List<MuscleRecovery>
}