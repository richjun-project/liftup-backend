package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_workout_plans",
    indexes = [Index(name = "idx_user_active_plan", columnList = "user_id, status")])
class UserWorkoutPlan(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    val sourceType: PlanSourceType,

    @Column(name = "source_id", length = 50)
    val sourceId: String? = null,

    @Column(name = "plan_name", nullable = false, length = 100)
    val planName: String,

    @Column(name = "plan_description", columnDefinition = "TEXT")
    val planDescription: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, length = 20)
    val splitType: SplitType,

    @Column(name = "total_days", nullable = false)
    val totalDays: Int,

    @Column(name = "current_day", nullable = false)
    var currentDay: Int = 1,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: PlanStatus = PlanStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @Column(name = "progression_model", nullable = false, length = 20)
    val progressionModel: ProgressionModel = ProgressionModel.LINEAR,

    @Column(name = "deload_every_n_weeks", nullable = false)
    val deloadEveryNWeeks: Int = 4,

    @Column(name = "ai_coaching_notes", columnDefinition = "TEXT")
    val aiCoachingNotes: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
