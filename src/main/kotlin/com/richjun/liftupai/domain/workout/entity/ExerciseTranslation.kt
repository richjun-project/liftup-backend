package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(
    name = "exercise_translations",
    uniqueConstraints = [
        UniqueConstraint(name = "uk_exercise_translation_locale", columnNames = ["exercise_id", "locale"])
    ],
    indexes = [
        Index(name = "idx_exercise_translation_locale_name", columnList = "locale, display_name")
    ]
)
data class ExerciseTranslation(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(nullable = false, length = 10)
    val locale: String,

    @Column(name = "display_name", nullable = false)
    val displayName: String,

    @Column(columnDefinition = "TEXT")
    val instructions: String? = null,

    @Column(columnDefinition = "TEXT")
    val tips: String? = null
)
