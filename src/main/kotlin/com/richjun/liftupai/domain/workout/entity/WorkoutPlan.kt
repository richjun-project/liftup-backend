package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "workout_plans")
data class WorkoutPlan(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, length = 100)
    var name: String,

    @Column(nullable = false)
    var weeklyDays: Int,

    @Column(nullable = false, length = 20)
    var splitType: String, // full_body, upper_lower, ppl, push_pull, bro_split

    @Column
    var programDurationWeeks: Int = 8,

    @Column(columnDefinition = "JSON")
    var schedule: String? = null, // JSON string of weekly schedule

    @Column
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var updatedAt: LocalDateTime = LocalDateTime.now()
)