package com.richjun.liftupai.domain.auth.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "device_sessions")
data class DeviceSession(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "device_id", nullable = false)
    val deviceId: String,

    @Column(columnDefinition = "JSON")
    val deviceInfo: String? = null,

    @Column(name = "last_login")
    val lastLogin: LocalDateTime = LocalDateTime.now(),

    @Column(name = "is_active")
    var isActive: Boolean = true,

    @Column(name = "created_at")
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now()
)