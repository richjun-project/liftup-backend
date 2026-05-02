package com.richjun.liftupai.domain.workout.repository

import com.richjun.liftupai.domain.workout.entity.Exercise
import com.richjun.liftupai.domain.workout.entity.ExerciseCategory
import com.richjun.liftupai.domain.workout.entity.Equipment
import com.richjun.liftupai.domain.workout.entity.MuscleGroup
import com.richjun.liftupai.domain.workout.entity.RecommendationTier
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ExerciseRepository : JpaRepository<Exercise, Long> {
    fun findBySlug(slug: String): Exercise?

    fun findAllByIsPlanEligibleTrue(): List<Exercise>

    fun findByIsPlanEligibleTrueAndCategory(category: ExerciseCategory): List<Exercise>

    @Query("SELECT e FROM Exercise e WHERE e.isPlanEligible = true AND e.category = :category ORDER BY e.popularity DESC")
    fun findPlanEligibleByCategoryOrderByPopularity(@Param("category") category: ExerciseCategory): List<Exercise>

    fun findByNameContainingIgnoreCase(name: String): List<Exercise>

    fun findFirstByCategoryAndRecommendationTierOrderByPopularityDesc(
        category: ExerciseCategory,
        tier: RecommendationTier
    ): Exercise?
    fun findByCategory(category: ExerciseCategory): List<Exercise>

    fun findByEquipment(equipment: Equipment): List<Exercise>

    fun findByCategoryAndEquipment(category: ExerciseCategory, equipment: Equipment): List<Exercise>

    // 페이징 쿼리
    fun findByCategory(category: ExerciseCategory, pageable: Pageable): Page<Exercise>

    fun findByCategoryAndEquipment(category: ExerciseCategory, equipment: Equipment, pageable: Pageable): Page<Exercise>

    /**
     * 사용자에게 노출할 수 있는 운동만 페이징 (ESSENTIAL + STANDARD).
     * ADVANCED/SPECIALIZED는 자동 추천/리스트에서 제외 — 검색 시에만 노출.
     * 인기도 내림차순 정렬.
     */
    @Query("""
        SELECT e FROM Exercise e
        WHERE e.recommendationTier IN (
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL,
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.STANDARD
        )
        ORDER BY e.popularity DESC, e.id ASC
    """)
    fun findListable(pageable: Pageable): Page<Exercise>

    @Query("""
        SELECT e FROM Exercise e
        WHERE e.category = :category
        AND e.recommendationTier IN (
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL,
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.STANDARD
        )
        ORDER BY e.popularity DESC, e.id ASC
    """)
    fun findListableByCategory(@Param("category") category: ExerciseCategory, pageable: Pageable): Page<Exercise>

    /**
     * 유사도 기반 정렬을 위해 카테고리 내 노출 가능한 모든 운동을 페이징 없이 조회.
     * EAGER인 muscleGroups를 JOIN FETCH로 함께 로드해 N+1 쿼리를 회피한다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        LEFT JOIN FETCH e.muscleGroups
        WHERE e.category = :category
        AND e.recommendationTier IN (
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL,
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.STANDARD
        )
    """)
    fun findAllListableByCategory(@Param("category") category: ExerciseCategory): List<Exercise>

    /**
     * 유사도 기반 정렬을 위해 노출 가능한 모든 운동을 페이징 없이 조회.
     * EAGER인 muscleGroups를 JOIN FETCH로 함께 로드해 N+1 쿼리를 회피한다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        LEFT JOIN FETCH e.muscleGroups
        WHERE e.recommendationTier IN (
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.ESSENTIAL,
            com.richjun.liftupai.domain.workout.entity.RecommendationTier.STANDARD
        )
    """)
    fun findAllListable(): List<Exercise>

    @Query("""
        SELECT DISTINCT e
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE (LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(t.displayName) LIKE LOWER(CONCAT('%', :search, '%')))
    """,
    countQuery = """
        SELECT COUNT(DISTINCT e)
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE (LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(t.displayName) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun searchPaged(@Param("search") search: String, pageable: Pageable): Page<Exercise>

    @Query("""
        SELECT DISTINCT e
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE e.category = :category
        AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(t.displayName) LIKE LOWER(CONCAT('%', :search, '%')))
    """,
    countQuery = """
        SELECT COUNT(DISTINCT e)
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE e.category = :category
        AND (LOWER(e.name) LIKE LOWER(CONCAT('%', :search, '%'))
           OR LOWER(t.displayName) LIKE LOWER(CONCAT('%', :search, '%')))
    """)
    fun searchByCategory(@Param("category") category: ExerciseCategory, @Param("search") search: String, pageable: Pageable): Page<Exercise>

    @Query(
        """
        SELECT DISTINCT e
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE LOWER(e.name) LIKE LOWER(CONCAT('%', :name, '%'))
           OR LOWER(t.displayName) LIKE LOWER(CONCAT('%', :name, '%'))
        """
    )
    fun searchByName(name: String): List<Exercise>

    @Query(
        """
        SELECT DISTINCT e
        FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE LOWER(e.name) = LOWER(:name)
           OR LOWER(t.displayName) = LOWER(:name)
        """
    )
    fun findByNameIgnoreCase(@Param("name") name: String): Exercise?

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
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE LOWER(e.name) IN :names
           OR LOWER(t.displayName) IN :names
    """)
    fun findByNameIn(@Param("names") names: List<String>): List<Exercise>

    /**
     * 정확한 이름 매칭을 시도하고, 없으면 부분 매칭을 시도합니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        LEFT JOIN ExerciseTranslation t ON t.exercise = e
        WHERE LOWER(TRIM(e.name)) = LOWER(TRIM(:name))
        OR LOWER(REPLACE(e.name, ' ', '')) = LOWER(REPLACE(:name, ' ', ''))
        OR LOWER(TRIM(t.displayName)) = LOWER(TRIM(:name))
        OR LOWER(REPLACE(t.displayName, ' ', '')) = LOWER(REPLACE(:name, ' ', ''))
    """)
    fun findByExactOrCompactName(@Param("name") name: String): List<Exercise>

    /**
     * 특정 근육 그룹을 포함하는 운동들을 조회합니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        JOIN e.muscleGroups mg
        WHERE mg IN :muscleGroups
    """)
    fun findByMuscleGroupsIn(@Param("muscleGroups") muscleGroups: List<MuscleGroup>): List<Exercise>

    /**
     * 특정 카테고리와 근육 그룹을 가진 운동들을 조회합니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        JOIN e.muscleGroups mg
        WHERE e.category = :category
        AND mg IN :muscleGroups
    """)
    fun findByCategoryAndMuscleGroups(
        @Param("category") category: ExerciseCategory,
        @Param("muscleGroups") muscleGroups: List<MuscleGroup>
    ): List<Exercise>

    /**
     * 특정 운동과 유사한 카테고리/근육 그룹을 가진 대체 운동을 찾습니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        JOIN e.muscleGroups mg
        WHERE e.id != :exerciseId
        AND e.category = :category
        AND mg IN :muscleGroups
        ORDER BY e.name
    """)
    fun findAlternativeExercises(
        @Param("exerciseId") exerciseId: Long,
        @Param("category") category: ExerciseCategory,
        @Param("muscleGroups") muscleGroups: List<MuscleGroup>
    ): List<Exercise>

    /**
     * 장비와 근육 그룹으로 대체 운동을 찾습니다.
     */
    @Query("""
        SELECT DISTINCT e FROM Exercise e
        JOIN e.muscleGroups mg
        WHERE e.equipment = :equipment
        AND mg IN :muscleGroups
        ORDER BY e.name
    """)
    fun findByEquipmentAndMuscleGroups(
        @Param("equipment") equipment: Equipment,
        @Param("muscleGroups") muscleGroups: List<MuscleGroup>
    ): List<Exercise>
}
