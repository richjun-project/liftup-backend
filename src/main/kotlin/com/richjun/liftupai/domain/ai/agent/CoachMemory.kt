package com.richjun.liftupai.domain.ai.agent

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * AI PT 가 기억하는 이 사용자에 대한 사실 하나("주말엔 운동 못 함", "집에 덤벨 10kg 두 개뿐", "왼쪽 무릎 수술 이력").
 * 대화 중 트레이너가 remember 도구로 남기고, 다음 대화부터 프롬프트에 들어간다. 사용자는 앱에서 보고 지울 수 있다.
 */
@Entity
@Table(name = "coach_memories")
class CoachMemory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(nullable = false, length = 32)
    var category: String,

    @Column(nullable = false, length = 300)
    var content: String,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        val CATEGORIES = setOf("preference", "schedule", "equipment", "health", "goal", "life", "other")
        const val MAX_PER_USER = 30
        const val MAX_LENGTH = 300
    }
}

interface CoachMemoryRepository : JpaRepository<CoachMemory, Long> {
    fun findByUserIdOrderByUpdatedAtDesc(userId: Long): List<CoachMemory>
    fun findByIdAndUserId(id: Long, userId: Long): CoachMemory?
    fun countByUserId(userId: Long): Long
    fun deleteByUserId(userId: Long)
}
