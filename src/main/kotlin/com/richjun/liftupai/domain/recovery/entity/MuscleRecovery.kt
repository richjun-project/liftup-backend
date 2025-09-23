package com.richjun.liftupai.domain.recovery.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "muscle_recovery")
data class MuscleRecovery(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val muscleGroup: String,

    @Column(nullable = false)
    var lastWorked: LocalDateTime,

    @Column(nullable = false)
    var recoveryPercentage: Int = 100,

    @Column(nullable = false)
    var feelingScore: Int = 5,

    @Column(nullable = false)
    var soreness: Int = 0,

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)