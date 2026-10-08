package com.richjun.liftupai.domain.analytics

import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 앱이 보낸 활성화 이벤트 한 건(첫 운동 퍼널). GA4 에 가는 이벤트 가운데 허용 목록만 서버에도 남긴다 —
 * GA4 를 열지 않고도 로그 분석에서 "어느 버전·플랫폼에서 어디서 멈췄나"를 볼 수 있게.
 * user_id 는 일반 컬럼(User 와 연관관계를 걸지 않는다 — 탈퇴 삭제는 UserService 가 직접 한다).
 */
@Entity
@Table(
    name = "app_events",
    indexes = [
        Index(name = "idx_app_events_user_created", columnList = "user_id,created_at"),
        Index(name = "idx_app_events_name_created", columnList = "name,created_at")
    ]
)
class AppEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 40)
    val name: String,

    /** 정제한 평평한 JSON(문자열 값은 코드값만 — 자유 입력은 받지 않는다) */
    @Column(length = 2000)
    val params: String? = null,

    @Column(name = "app_version", length = 32)
    val appVersion: String? = null,

    @Column(length = 16)
    val platform: String? = null,

    /** Firebase Test Lab(Play 사전 출시 보고서) 기기 — 분석에서 뺀다 */
    @Column(name = "test_device", nullable = false)
    val testDevice: Boolean = false,

    /** 앱이 이벤트를 만든 시각(UTC). 시계가 크게 틀린 값은 버리고 null */
    @Column(name = "occurred_at")
    val occurredAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = AppTime.utcNow()
)
