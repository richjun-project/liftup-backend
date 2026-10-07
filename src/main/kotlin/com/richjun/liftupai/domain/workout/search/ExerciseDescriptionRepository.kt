package com.richjun.liftupai.domain.workout.search

import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

/**
 * 검색 인덱스 빌드용 설명문 투영 — (exercise_id, locale, instructions, tips).
 * 이름 투영(ExerciseTranslationRepository.findAllNameRows)과 같은 이유로 LAZY exercise 프록시를 건드리지 않는 스칼라 투영만 쓴다.
 * 검색 패키지 안에 둔 것은 동작 설명문 검색 계층이 이 패키지의 관심사이기 때문이다.
 */
@Repository
interface ExerciseDescriptionRepository : JpaRepository<ExerciseTranslation, Long> {
    @Query("SELECT et.exercise.id, et.locale, et.instructions, et.tips FROM ExerciseTranslation et")
    fun findAllDescriptionRows(): List<Array<Any?>>
}
