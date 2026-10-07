package com.richjun.liftupai.domain.workout.favorite

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
interface ExerciseFavoriteRepository : JpaRepository<ExerciseFavorite, Long> {

    fun existsByUserIdAndExerciseId(userId: Long, exerciseId: Long): Boolean

    fun countByUserId(userId: Long): Long

    /** 즐겨찾기한 운동 id — 최근에 추가한 순 */
    @Query("SELECT f.exerciseId FROM ExerciseFavorite f WHERE f.userId = :userId ORDER BY f.createdAt DESC, f.id DESC")
    fun findExerciseIdsByUserId(@Param("userId") userId: Long): List<Long>

    /** 주어진 운동 id 중 즐겨찾기한 것만 (목록 한 페이지에 is_favorite 를 붙일 때 쓰는 쿼리 1개) */
    @Query("SELECT f.exerciseId FROM ExerciseFavorite f WHERE f.userId = :userId AND f.exerciseId IN :exerciseIds")
    fun findFavoriteIdsIn(@Param("userId") userId: Long, @Param("exerciseIds") exerciseIds: Collection<Long>): List<Long>

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ExerciseFavorite f WHERE f.userId = :userId AND f.exerciseId = :exerciseId")
    fun deleteByUserIdAndExerciseId(@Param("userId") userId: Long, @Param("exerciseId") exerciseId: Long): Int

    /** 회원 탈퇴 시 호출 (연결은 통합 단계에서 계정 삭제 서비스가 한다) */
    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ExerciseFavorite f WHERE f.userId = :userId")
    fun deleteAllByUserId(@Param("userId") userId: Long): Int
}
