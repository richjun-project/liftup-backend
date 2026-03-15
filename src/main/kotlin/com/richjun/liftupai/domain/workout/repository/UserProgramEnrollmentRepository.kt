package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.entity.EnrollmentStatus
import com.richjun.liftupai.domain.workout.entity.UserProgramEnrollment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface UserProgramEnrollmentRepository : JpaRepository<UserProgramEnrollment, Long> {
    fun findByUserAndStatus(user: User, status: EnrollmentStatus): List<UserProgramEnrollment>

    fun findFirstByUserAndStatusOrderByStartDateDesc(
        user: User,
        status: EnrollmentStatus
    ): UserProgramEnrollment?
}
