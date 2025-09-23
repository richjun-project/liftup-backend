package com.richjun.liftupai.domain.user.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_settings")
data class UserSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    // Notification Settings
    @Column(name = "workout_reminder")
    var workoutReminder: Boolean = true,

    @Column(name = "ai_messages")
    var aiMessages: Boolean = true,

    @Column
    var achievements: Boolean = true,

    @Column
    var marketing: Boolean = false,

    // Privacy Settings
    @Column(name = "share_progress")
    var shareProgress: Boolean = false,

    @Column(name = "public_profile")
    var publicProfile: Boolean = false,

    // App Settings
    @Column
    var theme: String = "LIGHT",

    @Column
    var language: String = "ko",

    @Column
    var units: String = "METRIC",

    // Workout Preferences
    @Column(name = "weekly_workout_days")
    var weeklyWorkoutDays: Int? = null,

    @Column(name = "workout_split")
    var workoutSplit: String? = null,

    @Column(name = "preferred_workout_time")
    var preferredWorkoutTime: String? = null,

    @Column(name = "workout_duration")
    var workoutDuration: Int? = null,

    @ElementCollection
    @CollectionTable(
        name = "user_available_equipment",
        joinColumns = [JoinColumn(name = "settings_id")]
    )
    @Column(name = "equipment")
    var availableEquipment: MutableSet<String> = mutableSetOf(),

    @ElementCollection
    @CollectionTable(
        name = "user_settings_injuries",
        joinColumns = [JoinColumn(name = "settings_id")]
    )
    @Column(name = "injury")
    var injuries: MutableSet<String> = mutableSetOf(),

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)