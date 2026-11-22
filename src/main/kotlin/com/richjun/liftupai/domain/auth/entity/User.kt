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

    // HS512 JWT + 이메일 클레임 — 긴 이메일이면 255자를 넘는다(V20260927001)
    @Column(length = 1024)
    var refreshToken: String? = null,

    // 이 시각(UTC, 초) 이후 발급된 이 사용자의 리프레시 토큰은 받아 준다 — 갱신 응답을 못 받은 앱이
    // 직전 토큰으로 다시 와도 로그아웃되지 않게(V20261122001, RefreshTokenFamily). null 이면 저장된 토큰과 같아야만.
    @Column(name = "refresh_floor_at")
    var refreshFloorAt: LocalDateTime? = null,

    @Column(name = "device_id", unique = true)
    var deviceId: String? = null,

    @Column(columnDefinition = "JSON")
    var deviceInfo: String? = null,

    @Column(name = "is_device_account")
    var isDeviceAccount: Boolean = false,

    @Column(name = "email_verified")
    var emailVerified: Boolean = false,

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null,

    @Column(name = "oauth_provider")
    var oauthProvider: String? = null,

    @Column(name = "oauth_id")
    var oauthId: String? = null
)

enum class UserLevel {
    BEGINNER,
    INTERMEDIATE,
    ADVANCED,
    EXPERT
}