package com.richjun.liftupai.domain.workout.entity

import jakarta.persistence.*

@Entity
@Table(name = "exercises")
data class Exercise(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val name: String,

    @Enumerated(EnumType.STRING)
    val category: ExerciseCategory,

    @ElementCollection(targetClass = MuscleGroup::class, fetch = FetchType.EAGER)
    @CollectionTable(name = "exercise_muscle_groups", joinColumns = [JoinColumn(name = "exercise_id")])
    @Column(name = "muscle_groups")  // 명시적으로 컬럼명 지정
    @Enumerated(EnumType.STRING)
    val muscleGroups: MutableSet<MuscleGroup> = mutableSetOf(),

    @Enumerated(EnumType.STRING)
    val equipment: Equipment? = null,

    @Column(columnDefinition = "TEXT")
    val instructions: String? = null,

    @Column
    val imageUrl: String? = null,

    @Column(name = "vector_id")
    var vectorId: String? = null,

    /**
     * 운동 인기도 (1-100)
     * - 90-100: 매우 인기 (Big 3 등)
     * - 70-89: 인기 (일반적인 운동)
     * - 50-69: 보통
     * - 30-49: 낮은 인기
     * - 1-29: 매우 낮음 (특수한 운동)
     */
    @Column(nullable = false, columnDefinition = "INT DEFAULT 50")
    val popularity: Int = 50,

    /**
     * 운동 난이도 (1-100)
     * - 1-30: 초보자 (bodyweight, machine)
     * - 31-60: 중급자 (barbell, dumbbell 기본)
     * - 61-80: 고급자 (복잡한 동작)
     * - 81-100: 전문가 (Olympic lifts 등)
     */
    @Column(nullable = false, columnDefinition = "INT DEFAULT 50")
    val difficulty: Int = 50,

    /**
     * 기본 운동 여부
     * - Big 3: 벤치프레스, 스쿼트, 데드리프트
     * - 기초 운동: 모든 초보자가 배워야 하는 운동
     */
    @Column(nullable = false, columnDefinition = "BOOLEAN DEFAULT false")
    val isBasicExercise: Boolean = false,

    /**
     * 운동 추천 등급
     * - ESSENTIAL: 필수 기본 운동 (30-40개) - 모든 사용자에게 추천
     * - STANDARD: 표준 운동 (80-100개) - 중급자 이상 추천
     * - ADVANCED: 고급 운동 (50-70개) - 고급자 이상 추천
     * - SPECIALIZED: 전문/특수 운동 - 검색 시에만 노출
     *
     * 추천 로직에서만 사용되며 데이터는 모두 보존됨
     */
    @Column(nullable = false, columnDefinition = "VARCHAR(20) DEFAULT 'STANDARD'")
    @Enumerated(EnumType.STRING)
    val recommendationTier: RecommendationTier = RecommendationTier.STANDARD
)