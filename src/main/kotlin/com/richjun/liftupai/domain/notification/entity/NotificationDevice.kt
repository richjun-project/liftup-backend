package com.richjun.liftupai.domain.notification.entity

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "notification_devices")
data class NotificationDevice(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(nullable = false, unique = true)
    var deviceToken: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var platform: DevicePlatform,

    @Column
    var deviceName: String? = null,

    @Column
    var appVersion: String? = null,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column(nullable = false)
    val registeredAt: LocalDateTime = AppTime.utcNow(),

    @Column(nullable = false)
    var lastUsedAt: LocalDateTime = AppTime.utcNow()
)

enum class DevicePlatform {
    ANDROID,
    IOS,
    WEB
}