package com.richjun.liftupai.domain.workout.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDate

@Entity
@Table(name = "workout_streaks")
data class WorkoutStreak(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    val date: LocalDate,

    @Column(nullable = false)
    val currentStreak: Int = 1,

    @Column(nullable = false)
    val longestStreak: Int = 1
)