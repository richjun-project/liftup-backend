package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "user_exercise_overrides",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_user_exercise_overrides_enrollment_original",
            columnNames = ["enrollment_id", "original_exercise_id"]
        )
    ]
)
class UserExerciseOverride(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id", nullable = false)
    val enrollment: UserProgramEnrollment,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_exercise_id", nullable = false)
    val originalExercise: Exercise,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "substitute_exercise_id", nullable = false)
    val substituteExercise: Exercise,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val reason: SubstitutionReason
)
