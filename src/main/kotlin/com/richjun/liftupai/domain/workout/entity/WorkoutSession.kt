package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "workout_sessions")
data class WorkoutSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val startTime: LocalDateTime = LocalDateTime.now(),

    @Column
    var endTime: LocalDateTime? = null,

    @Column
    var duration: Int? = null,  // 분 단위

    @Column
    var totalVolume: Double? = null,  // kg

    @Column
    var caloriesBurned: Int? = null,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var status: SessionStatus = SessionStatus.IN_PROGRESS,

    @Column
    var name: String? = null,

    @Column
    var isActive: Boolean = true,

    @Column
    var syncedFromOffline: Boolean = false,

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    var workoutType: WorkoutType? = null,

    @Column
    var programDay: Int? = null,

    @Column
    var programCycle: Int? = null,

    @Column(length = 50)
    var recommendationType: String? = null
)