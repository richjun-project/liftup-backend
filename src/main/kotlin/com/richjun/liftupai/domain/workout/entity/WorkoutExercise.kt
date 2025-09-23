package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "workout_exercises")
data class WorkoutExercise(
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
    val orderInSession: Int,

    @OneToMany(mappedBy = "workoutExercise", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    val sets: MutableList<ExerciseSet> = mutableListOf(),

    @Column
    var totalVolume: Double? = null,  // kg

    @Column(columnDefinition = "TEXT")
    var notes: String? = null
)