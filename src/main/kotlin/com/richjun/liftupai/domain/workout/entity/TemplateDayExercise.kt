package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "template_day_exercises")
class TemplateDayExercise(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_day_id", nullable = false)
    val templateDay: TemplateDay,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(name = "order_in_day", nullable = false)
    val orderInDay: Int,

    @Column(nullable = false)
    val sets: Int,

    @Column(name = "min_reps", nullable = false)
    val minReps: Int,

    @Column(name = "max_reps", nullable = false)
    val maxReps: Int,

    @Column(name = "rest_seconds", nullable = false)
    val restSeconds: Int = 90,

    @Column(name = "is_compound", nullable = false)
    val isCompound: Boolean = false,

    @Column(name = "target_rpe", nullable = false)
    val targetRPE: Double = 7.0,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null
)
