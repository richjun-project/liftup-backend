package com.richjun.liftupai.domain.notification.entity

import com.richjun.liftupai.domain.auth.entity.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "notification_history")
data class NotificationHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, unique = true)
    val notificationId: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val type: NotificationType,

    @Column(nullable = false)
    val title: String,

    @Column(nullable = false, length = 1000)
    val body: String,

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
        name = "notification_data",
        joinColumns = [JoinColumn(name = "notification_history_id")]
    )
    @MapKeyColumn(name = "data_key")
    @Column(name = "data_value")
    val data: MutableMap<String, String> = mutableMapOf(),

    @Column(nullable = false)
    var isRead: Boolean = false,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column
    var readAt: LocalDateTime? = null,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id")
    val schedule: NotificationSchedule? = null
)