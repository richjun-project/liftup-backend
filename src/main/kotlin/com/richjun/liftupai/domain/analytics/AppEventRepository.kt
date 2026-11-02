package com.richjun.liftupai.domain.analytics

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

interface AppEventRepository : JpaRepository<AppEvent, Long> {
    fun findByUserIdOrderByIdAsc(userId: Long): List<AppEvent>

    /** 테스트 기기(Play 심사·사전 출시 로봇)에서 온 이벤트가 하나라도 있는 사용자인가 — KPI 의 테스트 사용자와 같은 기준 */
    fun existsByUserIdAndTestDeviceTrue(userId: Long): Boolean

    @Transactional
    @Modifying
    @Query("DELETE FROM AppEvent e WHERE e.createdAt < :cutoff")
    fun deleteOlderThan(@Param("cutoff") cutoff: LocalDateTime): Int
}
