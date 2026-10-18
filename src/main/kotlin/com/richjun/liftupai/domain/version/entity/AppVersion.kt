package com.richjun.liftupai.domain.version.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(
    name = "app_versions",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["version", "platform"])
    ]
)
class AppVersion(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val version: String,  // ex: "1.0.0", "1.2.3"

    @Column(name = "version_code", nullable = false)
    val versionCode: Int,  // ex: 100, 123

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val platform: Platform,

    @Column(name = "minimum_version")
    var minimumVersion: String? = null,  // 최소 필수 버전

    @Column(name = "minimum_version_code")
    var minimumVersionCode: Int? = null,

    @Column(name = "force_update", nullable = false)
    var forceUpdate: Boolean = false,  // 강제 업데이트 여부

    @Column(name = "update_url")
    var updateUrl: String? = null,  // 스토어 URL

    @Column(name = "release_notes", columnDefinition = "TEXT")
    var releaseNotes: String? = null,  // 릴리즈 노트

    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,

    @Column(name = "release_date")
    val releaseDate: LocalDateTime = LocalDateTime.now(),

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "maintenance_mode", nullable = false)
    var maintenanceMode: Boolean = false,  // 서버 점검 모드

    @Column(name = "maintenance_message", columnDefinition = "TEXT")
    var maintenanceMessage: String? = null,  // 점검 안내 메시지

    @Column(name = "features", columnDefinition = "JSON")
    var features: String? = null,  // 버전별 기능 플래그 (JSON)

    /**
     * 최신보다 낮은(필수는 아닌) 앱에 '나중에' 가 있는 권장 업데이트 안내창을 띄운다.
     * 1.1.29 이하 앱은 update_required=true 일 때만 안내창을 띄우므로, 켜져 있으면 RECOMMENDED 와 함께
     * update_required=true 를 보낸다(같은 기기엔 하루 한 번 — [UpdateNudgeThrottle]).
     */
    @Column(name = "recommend_update", nullable = false)
    var recommendUpdate: Boolean = false
)

enum class Platform {
    IOS,
    ANDROID,
    WEB,
    ALL
}

enum class UpdateType {
    OPTIONAL,      // 선택 업데이트
    RECOMMENDED,   // 권장 업데이트
    REQUIRED       // 필수 업데이트
}