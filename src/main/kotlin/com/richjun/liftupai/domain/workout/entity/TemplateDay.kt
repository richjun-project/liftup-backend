package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "template_days")
class TemplateDay(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    val template: WorkoutPlanTemplate,

    @Column(name = "day_number", nullable = false)
    val dayNumber: Int,

    @Column(name = "day_name", nullable = false, length = 50)
    val dayName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "workout_type", nullable = false, length = 20)
    val workoutType: WorkoutType,

    @Column(name = "estimated_duration_minutes")
    val estimatedDurationMinutes: Int = 60
)
