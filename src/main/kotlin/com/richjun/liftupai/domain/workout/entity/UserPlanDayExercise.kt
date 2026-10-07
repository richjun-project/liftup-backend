package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "user_plan_day_exercises")
class UserPlanDayExercise(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "plan_day_id", nullable = false)
    val planDay: UserPlanDay,

    /** 대체 운동 적용 시 교체된다 — UserPlanService.substituteExerciseInActivePlan */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "exercise_id", nullable = false)
    var exercise: Exercise,

    @Column(name = "order_in_day", nullable = false)
    val orderInDay: Int,

    @Column(nullable = false)
    var sets: Int,

    @Column(name = "min_reps", nullable = false)
    var minReps: Int,

    @Column(name = "max_reps", nullable = false)
    var maxReps: Int,

    @Column(name = "rest_seconds", nullable = false)
    var restSeconds: Int = 90,

    /** 대체 운동으로 교체될 때 새 운동 기준으로 재계산된다 */
    @Column(name = "is_compound", nullable = false)
    var isCompound: Boolean = false,

    @Column(name = "target_rpe", nullable = false)
    var targetRPE: Double = 7.0,

    @Column(columnDefinition = "TEXT")
    var notes: String? = null
)
