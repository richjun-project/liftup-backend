package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "program_day_exercises")
class ProgramDayExercise(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_day_id", nullable = false)
    val programDay: ProgramDay,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    val exercise: Exercise,

    @Column(nullable = false)
    val orderInDay: Int,

    @Column(nullable = false)
    val isCompound: Boolean = true,

    @Column(nullable = false)
    val sets: Int,

    @Column(nullable = false)
    val minReps: Int,

    @Column(nullable = false)
    val maxReps: Int,

    @Column(nullable = false)
    val restSeconds: Int,

    @Column(nullable = false)
    val targetRPE: Double = 7.0,

    @Column
    val intensityPercentLow: Int? = null,

    @Column
    val intensityPercentHigh: Int? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val setType: SetType = SetType.WORKING,

    @Column(nullable = false)
    val isOptional: Boolean = false,

    @Column(columnDefinition = "TEXT")
    val notes: String? = null
)
