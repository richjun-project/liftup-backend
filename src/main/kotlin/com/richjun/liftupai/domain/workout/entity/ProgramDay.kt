package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "program_days")
class ProgramDay(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "program_id", nullable = false)
    val program: CanonicalProgram,

    @Column(nullable = false)
    val dayNumber: Int,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val workoutType: WorkoutType,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val estimatedDurationMinutes: Int = 60
)
