package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseTranslation
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ExerciseTranslationRepository : JpaRepository<ExerciseTranslation, Long> {
    fun findByExerciseAndLocale(exercise: Exercise, locale: String): ExerciseTranslation?

    fun findByExerciseIdAndLocale(exerciseId: Long, locale: String): ExerciseTranslation?

    fun findByExerciseIdInAndLocale(exerciseIds: Collection<Long>, locale: String): List<ExerciseTranslation>

    /** 검색 인덱스 빌드용 (exercise_id, locale, display_name) 투영 — LAZY exercise 프록시를 건드리지 않는다 */
    @Query("SELECT et.exercise.id, et.locale, et.displayName FROM ExerciseTranslation et")
    fun findAllNameRows(): List<Array<Any>>

    fun deleteByExerciseIdIn(exerciseIds: Collection<Long>)

    @Query(
        """
        SELECT et
        FROM ExerciseTranslation et
        WHERE et.locale = :locale
        AND LOWER(et.displayName) LIKE LOWER(CONCAT('%', :query, '%'))
        """
    )
    fun searchByDisplayName(
        @Param("locale") locale: String,
        @Param("query") query: String
    ): List<ExerciseTranslation>
}
