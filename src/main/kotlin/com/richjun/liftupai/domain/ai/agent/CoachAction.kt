package com.richjun.liftupai.domain.ai.agent

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * 트레이너가 사용자의 플랜·설정을 바꾼 기록 하나(2026-11-14 coach-autopilot-r2, "코치가 한 일").
 * 바꾸기 전 상태(snapshot JSON: 활성 플랜 종목 전체·현재 Day·장비·운동 시간·메시지 설정)와 바꾼 뒤 플랜 확인값을 함께 남겨
 * 사용자가 앱에서 보고 한 번에 되돌릴 수 있게 한다. 자동 조정(오토파일럿)도 같은 기록을 쓴다(source=autopilot).
 */
@Entity
@Table(name = "coach_actions")
class CoachAction(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    /** 도구 이름(swap_exercise_in_plan …) */
    @Column(nullable = false, length = 40)
    val tool: String,

    /** chat | autopilot */
    @Column(nullable = false, length = 16)
    val source: String = "chat",

    /** 사용자 언어로 쓴 한 줄 요약("스쿼트 → 레그 프레스") */
    @Column(nullable = false, length = 200)
    val summary: String,

    @Column(columnDefinition = "TEXT", nullable = false)
    val snapshot: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "undone_at")
    var undoneAt: LocalDateTime? = null,
)

interface CoachActionRepository : JpaRepository<CoachAction, Long> {
    fun findTop20ByUserIdOrderByIdDesc(userId: Long): List<CoachAction>
    fun findFirstByUserIdOrderByIdDesc(userId: Long): CoachAction?
    fun findByIdAndUserId(id: Long, userId: Long): CoachAction?
}
