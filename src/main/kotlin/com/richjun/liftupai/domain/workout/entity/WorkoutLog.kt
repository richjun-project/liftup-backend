package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "workout_logs")
data class WorkoutLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    val session: WorkoutSession,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(nullable = false)
    val setNumber: Int,

    @Column(nullable = false)
    val weight: Double,

    @Column(nullable = false)
    val reps: Int,

    @Column
    val restTime: Int? = null,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now()
)