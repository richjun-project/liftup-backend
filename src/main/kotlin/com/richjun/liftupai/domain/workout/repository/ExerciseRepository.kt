package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.Equipment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ExerciseRepository : JpaRepository<Exercise, Long> {
    fun findByCategory(category: ExerciseCategory): List<Exercise>

    fun findByEquipment(equipment: Equipment): List<Exercise>

    fun findByCategoryAndEquipment(category: ExerciseCategory, equipment: Equipment): List<Exercise>

    @Query("SELECT e FROM Exercise e WHERE LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%'))")
    fun searchByName(name: String): List<Exercise>

    fun findByNameIgnoreCase(name: String): Exercise?

    /**
     * 정규화된 이름으로 운동을 검색합니다.
     * 푸시/푸쉬, 레터럴/래터럴 등의 철자 변형을 처리합니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        WHERE LOWER(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(
            e.name, '푸쉬', '푸시'), '래터럴', '레터럴'), '프래스', '프레스'),
            '플레이', '플라이'), '덤밸', '덤벨'), '로오', '로우'))
        LIKE LOWER(CONCAT('%', :normalizedName, '%'))
    """)
    fun findByNormalizedName(@Param("normalizedName") normalizedName: String): List<Exercise>

    /**
     * 여러 이름 변형으로 운동을 검색합니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        WHERE LOWER(e.name) IN :names
    """)
    fun findByNameIn(@Param("names") names: List<String>): List<Exercise>

    /**
     * 정확한 이름 매칭을 시도하고, 없으면 부분 매칭을 시도합니다.
     */
    @Query("""
        SELECT e FROM Exercise e
        WHERE LOWER(TRIM(e.name)) = LOWER(TRIM(:name))
        OR LOWER(REPLACE(e.name, ' ', '')) = LOWER(REPLACE(:name, ' ', ''))
    """)
    fun findByExactOrCompactName(@Param("name") name: String): List<Exercise>
}