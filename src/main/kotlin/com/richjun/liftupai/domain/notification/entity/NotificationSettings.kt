package com.richjun.liftupai.domain.notification.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime

@Entity
@Table(name = "notification_settings")
data class NotificationSettings(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,

    @Column(nullable = false)
    var workoutReminder: Boolean = true,

    @Column
    var workoutReminderTime: LocalTime? = LocalTime.of(18, 0),

    @Column(nullable = false)
    var aiMessages: Boolean = true,

    @Column(nullable = false)
    var achievements: Boolean = true,

    @Column(nullable = false)
    var marketing: Boolean = false,

    @Column(nullable = false)
    var dailyReport: Boolean = false,

    @Column(nullable = false)
    var weeklyReport: Boolean = true,

    @Column(nullable = false)
    var socialUpdates: Boolean = true,

    @Column(nullable = false)
    var recoveryAlerts: Boolean = true,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)