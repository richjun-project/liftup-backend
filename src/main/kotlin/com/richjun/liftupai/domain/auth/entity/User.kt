package com.richjun.liftupai.domain.auth.entity

import com.richjun.liftupai.domain.user.entity.UserProfile
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(unique = true, nullable = true)
    val email: String? = null,

    @Column(nullable = true)
    var password: String? = null,

    @Column(nullable = false)
    var nickname: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var level: UserLevel = UserLevel.BEGINNER,

    @Column(nullable = false)
    val joinDate: LocalDateTime = LocalDateTime.now(),

    @OneToOne(mappedBy = "user", cascade = [CascadeType.ALL], fetch = FetchType.LAZY)
    var profile: UserProfile? = null,

    @Column(nullable = false)
    var isActive: Boolean = true,

    @Column
    var lastLoginAt: LocalDateTime? = null,

    @Column
    var refreshToken: String? = null,

    @Column(name = "device_id", unique = true)
    var deviceId: String? = null,

    @Column(columnDefinition = "JSON")
    var deviceInfo: String? = null,

    @Column(name = "is_device_account")
    var isDeviceAccount: Boolean = false,

    @Column(name = "email_verified")
    var emailVerified: Boolean = false,

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)

enum class UserLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
    EXPERT
}