package com.richjun.liftupai.domain.workout.favorite

import com.richjun.liftupai.global.time.AppTime
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 사용자의 즐겨찾기 운동. user_id 는 일반 컬럼(User 와 연관관계를 걸지 않는다 — 탈퇴 삭제는 deleteAllByUserId 로).
 * exercise_id 도 일반 컬럼이며 DB 에서는 FK(ON DELETE CASCADE)로 카탈로그 정리를 따라간다.
 */
@Entity
@Table(
    name = "exercise_favorites",
    uniqueConstraints = [UniqueConstraint(name = "uk_exercise_favorites_user_exercise", columnNames = ["user_id", "exercise_id"])],
    indexes = [Index(name = "idx_exercise_favorites_user_created", columnList = "user_id,created_at")]
)
class ExerciseFavorite(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: Long,

    @Column(name = "exercise_id", nullable = false)
    val exerciseId: Long,

    @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = AppTime.utcNow()
)
