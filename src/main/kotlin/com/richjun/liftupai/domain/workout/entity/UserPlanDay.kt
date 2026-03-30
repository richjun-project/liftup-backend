package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_plan_days",
    uniqueConstraints = [UniqueConstraint(name = "uk_plan_day", columnNames = ["plan_id", "day_number"])])
class UserPlanDay(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_id", nullable = false)
    val plan: UserWorkoutPlan,

    @Column(name = "day_number", nullable = false)
    val dayNumber: Int,

    @Column(name = "day_name", nullable = false, length = 50)
    val dayName: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "workout_type", nullable = false, length = 20)
    val workoutType: WorkoutType,

    @Column(name = "estimated_duration_minutes")
    val estimatedDurationMinutes: Int = 60,

    @Column(name = "total_completions", nullable = false)
    var totalCompletions: Int = 0,

    @Column(name = "last_completed_at")
    var lastCompletedAt: LocalDateTime? = null
)
