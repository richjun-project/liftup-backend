package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "workout_plan_templates",
    uniqueConstraints = [UniqueConstraint(name = "uk_templates_code", columnNames = ["code"])])
class WorkoutPlanTemplate(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 50, unique = true)
    val code: String,

    @Column(nullable = false, length = 100)
    val name: String,

    @Column(columnDefinition = "TEXT")
    val description: String? = null,

    @Column(name = "target_goal", nullable = false, length = 30)
    val targetGoal: String,

    @Column(name = "target_experience", nullable = false, length = 20)
    val targetExperience: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "split_type", nullable = false, length = 20)
    val splitType: SplitType,

    @Column(name = "total_days", nullable = false)
    val totalDays: Int,

    @Column(name = "estimated_weeks", nullable = false)
    val estimatedWeeks: Int = 8,

    @Column(name = "icon_name", length = 50)
    val iconName: String? = null,

    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,

    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,

    @Column(name = "is_premium", nullable = false)
    val isPremium: Boolean = false,

    // AI generated plan owner (null = system preset)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id")
    val ownerUser: User? = null,

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    val sourceType: PlanSourceType = PlanSourceType.PRESET,

    @Column(name = "ai_coaching_notes", columnDefinition = "TEXT")
    val aiCoachingNotes: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
