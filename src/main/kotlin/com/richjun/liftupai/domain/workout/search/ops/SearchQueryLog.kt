package com.richjun.liftupai.domain.workout.search.ops

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 검색 한 번의 운영 기록. 사용자 식별자·부상 목록은 일부러 컬럼이 없다(개인정보 최소화) —
 * 질의 문자열도 정규화본(소문자·공백 정리·길이 제한)이고 이메일·긴 숫자열이 든 질의는 아예 남기지 않는다.
 */
@Entity
@Table(
    name = "search_query_log",
    indexes = [
        Index(name = "idx_search_query_log_created", columnList = "created_at"),
        Index(name = "idx_search_query_log_zero", columnList = "result_count,created_at")
    ]
)
class SearchQueryLog(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "query_text", nullable = false, length = 100)
    val queryText: String,

    @Column(nullable = false, length = 10)
    val locale: String,

    @Column(name = "result_count", nullable = false)
    val resultCount: Int,

    @Column(name = "latency_ms", nullable = false)
    val latencyMs: Int,

    @Column(nullable = false, length = 20)
    val source: String,

    @Column(name = "has_filters", nullable = false)
    val hasFilters: Boolean = false,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime
)
