package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "exercise_substitutions")
class ExerciseSubstitution(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_exercise_id", nullable = false)
    val originalExercise: Exercise,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_exercise_id", nullable = false)
    val substituteExercise: Exercise,

    @Column(nullable = false)
    val priority: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: SubstitutionReason,

    @Column(nullable = false)
    val movementPattern: String,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null
)
