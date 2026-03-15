package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_program_enrollments")
class UserProgramEnrollment(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    val program: CanonicalProgram,

    @Column(nullable = false)
    val programVersion: Int,

    @Column(nullable = false)
    val startDate: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var totalCompletedWorkouts: Int = 0,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: EnrollmentStatus = EnrollmentStatus.ACTIVE,

    @Column
    var lastActiveDate: LocalDateTime? = null,

    @Column
    var endDate: LocalDateTime? = null,

    @Version
    val entityVersion: Long = 0
)
