package com.richjun.liftupai.domain.notification.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.LocalTime

@Entity
@Table(name = "notification_schedules")
data class NotificationSchedule(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false)
    var scheduleName: String,

    @Enumerated(EnumType.STRING)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "notification_schedule_days",
        joinColumns = [JoinColumn(name = "schedule_id")]
    )
    @Column(name = "day_of_week")
    var days: MutableSet<DayOfWeek> = mutableSetOf(),

    @Column(nullable = false)
    var time: LocalTime,

    @Column(nullable = false)
    var enabled: Boolean = true,

    @Column(length = 500)
    var message: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var notificationType: NotificationType = NotificationType.WORKOUT_REMINDER,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var nextTriggerAt: LocalDateTime? = null
)

enum class DayOfWeek {
    MON, TUE, WED, THU, FRI, SAT, SUN
}

enum class NotificationType {
    WORKOUT_REMINDER,
    ACHIEVEMENT,
    STREAK,
    REST_DAY,
    RECOVERY_ALERT,
    AI_MESSAGE
}