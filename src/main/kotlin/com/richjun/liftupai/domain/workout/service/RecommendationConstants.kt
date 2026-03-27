package com.richjun.liftupai.domain.workout.service

import com.richjun.liftupai.domain.user.entity.ExperienceLevel
import com.richjun.liftupai.domain.user.entity.FitnessGoal
import com.richjun.liftupai.domain.workout.entity.MuscleGroup

/**
 * 추천 시스템 전체에서 공유하는 상수.
 * ExerciseRecommendationService, VectorWorkoutRecommendationService 등에서 사용.
 */
object RecommendationConstants {
    /** 회복 필터 임계값 (%) — 이 값 미만이면 해당 근육 회피 */
    const val RECOVERY_THRESHOLD_PERCENT = 50

    /** 필터 후 최소 운동 개수 — 이 미만이면 필터 스킵/원본 반환 */
    const val SAFETY_MIN_EXERCISES = 3

    /** 최근 운동 회피 시간 (시간) — 폴백용 기본값 */
    const val RECENT_WORKOUT_HOURS = 24

    /** 프로필/설정 없을 때 기본 프로그램 타입 */
    const val DEFAULT_PROGRAM_TYPE = "FULL_BODY"

    /** 근육군별 최소 회복 시간 (시간) — NSCA 가이드라인 기반 */
    val MUSCLE_RECOVERY_HOURS: Map<MuscleGroup, Int> = mapOf(
        // 대근육 (48-72시간)
        MuscleGroup.QUADRICEPS to 60,
        MuscleGroup.HAMSTRINGS to 60,
        MuscleGroup.GLUTES to 60,
        MuscleGroup.LEGS to 60,
        MuscleGroup.BACK to 48,
        MuscleGroup.LATS to 48,
        MuscleGroup.CHEST to 48,
        // 중근육 (36-48시간)
        MuscleGroup.SHOULDERS to 36,
        MuscleGroup.TRAPS to 36,
        // 소근육 (24-36시간)
        MuscleGroup.BICEPS to 24,
        MuscleGroup.TRICEPS to 24,
        MuscleGroup.FOREARMS to 24,
        MuscleGroup.CALVES to 24,
        MuscleGroup.ABS to 24,
        MuscleGroup.CORE to 24,
        MuscleGroup.NECK to 24
    )

    /** 대근육군 집합 — 주당 빈도 상한 판별에 사용 */
    val LARGE_MUSCLE_GROUPS: Set<MuscleGroup> = setOf(
        MuscleGroup.QUADRICEPS, MuscleGroup.HAMSTRINGS, MuscleGroup.GLUTES,
        MuscleGroup.LEGS, MuscleGroup.BACK, MuscleGroup.LATS, MuscleGroup.CHEST
    )

    /** 경험 수준별 근육군당 주간 적정 세트 범위 */
    fun getWeeklyVolumeRange(level: ExperienceLevel): IntRange = when (level) {
        ExperienceLevel.BEGINNER -> 6..12
        ExperienceLevel.NOVICE -> 8..15
        ExperienceLevel.INTERMEDIATE -> 10..20
        ExperienceLevel.ADVANCED -> 12..25
        ExperienceLevel.EXPERT -> 15..30
    }

    /**
     * 근육군 간 시너지 계수
     * 주동근 운동 시 보조 근육에 부분 볼륨 가산
     * 예: 가슴 운동 → 삼두 0.5세트, 전면삼각 0.3세트 가산
     */
    val MUSCLE_SYNERGY: Map<MuscleGroup, Map<MuscleGroup, Double>> = mapOf(
        MuscleGroup.CHEST to mapOf(MuscleGroup.TRICEPS to 0.5, MuscleGroup.SHOULDERS to 0.3),
        MuscleGroup.BACK to mapOf(MuscleGroup.BICEPS to 0.5, MuscleGroup.FOREARMS to 0.3),
        MuscleGroup.LATS to mapOf(MuscleGroup.BICEPS to 0.5, MuscleGroup.FOREARMS to 0.3),
        MuscleGroup.SHOULDERS to mapOf(MuscleGroup.TRICEPS to 0.3),
        MuscleGroup.QUADRICEPS to mapOf(MuscleGroup.GLUTES to 0.4, MuscleGroup.CORE to 0.2),
        MuscleGroup.HAMSTRINGS to mapOf(MuscleGroup.GLUTES to 0.5, MuscleGroup.CORE to 0.2),
        MuscleGroup.GLUTES to mapOf(MuscleGroup.HAMSTRINGS to 0.3, MuscleGroup.CORE to 0.2)
    )

    /** 근육군별 최적 회복 일수 범위 (일) */
    fun getOptimalRecoveryDays(muscleGroup: MuscleGroup): IntRange {
        val hours = MUSCLE_RECOVERY_HOURS[muscleGroup] ?: 36
        val minDays = (hours / 24).coerceAtLeast(1)
        val maxDays = ((hours * 1.5) / 24).toInt().coerceAtLeast(minDays + 1)
        return minDays..maxDays
    }

    /**
     * 연령 기반 회복 시간 보정 계수
     * 40대 이상부터 회복 시간이 점진적으로 증가
     */
    fun getAgeRecoveryMultiplier(age: Int?): Double = when {
        age == null -> 1.0        // 나이 미입력: 기본값
        age < 30 -> 1.0           // 20대: 기준
        age < 40 -> 1.1           // 30대: 10% 더 긴 회복
        age < 50 -> 1.25          // 40대: 25% 더 긴 회복
        age < 60 -> 1.4           // 50대: 40% 더 긴 회복
        else -> 1.6               // 60대+: 60% 더 긴 회복
    }

    /**
     * 연령 기반 progression 보정 계수
     * 나이가 많을수록 무게 증가를 보수적으로
     */
    fun getAgeProgressionMultiplier(age: Int?): Double = when {
        age == null -> 1.0
        age < 30 -> 1.0
        age < 40 -> 0.95
        age < 50 -> 0.85
        age < 60 -> 0.75
        else -> 0.65
    }

    /**
     * 목표별 복합운동 최소 비율 (0.0-1.0)
     *
     * - STRENGTH: 근력 목표 → 70% 복합 (큰 무게·다관절 위주)
     * - MUSCLE_GAIN: 근비대 → 55% 복합 (볼륨 분산, 고립도 중요)
     * - WEIGHT_LOSS: 체중감량 → 75% 복합 (칼로리 소비 극대화)
     * - ENDURANCE: 근지구력 → 50% 복합 (고립·유산소 비중↑)
     * - GENERAL_FITNESS / ATHLETIC_PERFORMANCE: 일반 → 60% 복합
     *
     * 사용자의 goals가 여러 개인 경우, 가장 높은 비율을 적용
     */
    fun getCompoundRatio(goals: Set<FitnessGoal>?): Double {
        if (goals.isNullOrEmpty()) return 0.60
        return goals.maxOf { getCompoundRatioForGoal(it) }
    }

    private fun getCompoundRatioForGoal(goal: FitnessGoal): Double = when (goal) {
        FitnessGoal.STRENGTH -> 0.70
        FitnessGoal.MUSCLE_GAIN -> 0.55
        FitnessGoal.WEIGHT_LOSS -> 0.75
        FitnessGoal.ENDURANCE -> 0.50
        FitnessGoal.GENERAL_FITNESS -> 0.60
        FitnessGoal.ATHLETIC_PERFORMANCE -> 0.60
    }
}
