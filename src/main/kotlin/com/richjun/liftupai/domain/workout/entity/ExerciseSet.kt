package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "exercise_sets")
data class ExerciseSet(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workout_exercise_id", nullable = false)
    val workoutExercise: WorkoutExercise,

    @Column(nullable = false)
    val setNumber: Int,

    @Column(nullable = false)
    val weight: Double,  // kg

    @Column(nullable = false)
    val reps: Int,

    @Column
    val restTime: Int? = null,  // 초 단위

    @Column
    val rpe: Int? = null,  // Rate of Perceived Exertion (1-10)

    @Column
    val isPersonalRecord: Boolean = false,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null,

    @Column
    val completed: Boolean = false,

    @Column
    val completedAt: LocalDateTime? = null
)