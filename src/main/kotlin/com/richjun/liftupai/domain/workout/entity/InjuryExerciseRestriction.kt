package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "injury_exercise_restrictions")
class InjuryExerciseRestriction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50)
    val injuryType: String,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "restricted_exercise_id", nullable = false)
    val restrictedExercise: Exercise,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "suggested_substitute_id")
    val suggestedSubstitute: Exercise? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val severity: InjurySeverity,

    @Column(columnDefinition = "TEXT")
    val reason: String? = null
)
