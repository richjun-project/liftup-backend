package com.richjun.liftupai.domain.user.entity

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.time.AppTime
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

    @Column(name = "time_zone")
    var timeZone: String = "Asia/Seoul",

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

    /**
     * 온보딩에서 고른 집중 부위 목표(thick_legs·glutes·big_arms …, 소문자 앱 표기).
     * FitnessGoal(6종)로는 세부 목표가 사라져서 따로 저장한다 — 플랜 집중 볼륨·추천이 읽는다.
     */
    @ElementCollection
    @CollectionTable(
        name = "user_focus_goals",
        joinColumns = [JoinColumn(name = "settings_id")]
    )
    @Column(name = "focus_goal")
    var focusGoals: MutableSet<String> = mutableSetOf(),

    @Column(name = "created_at")
    val createdAt: LocalDateTime = AppTime.utcNow(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = AppTime.utcNow()
)
