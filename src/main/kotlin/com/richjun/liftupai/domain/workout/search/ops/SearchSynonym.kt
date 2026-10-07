package com.richjun.liftupai.domain.workout.search.ops

import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 런타임 동의어 한 줄. line 은 엔진 사전(ExerciseSearchLexicon)과 같은 형식이다:
 * "트리거|트리거 => 확장|확장" (강한 별칭은 "=>>"). enabled 인 줄만 쓰인다.
 */
@Entity
@Table(name = "search_synonyms")
class SearchSynonym(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, length = 500)
    val line: String,

    @Column(nullable = false)
    val enabled: Boolean = true,

    @Column(length = 200)
    val note: String? = null,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = AppTime.utcNow()
)
