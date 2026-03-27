package com.richjun.liftupai.domain.workout.service

/**
 * 추천 시스템 전체에서 공유하는 상수.
 * ExerciseRecommendationService, VectorWorkoutRecommendationService 등에서 사용.
 */
object RecommendationConstants {
    /** 회복 필터 임계값 (%) — 이 값 미만이면 해당 근육 회피 */
    const val RECOVERY_THRESHOLD_PERCENT = 50

    /** 필터 후 최소 운동 개수 — 이 미만이면 필터 스킵/원본 반환 */
    const val SAFETY_MIN_EXERCISES = 3

    /** 최근 운동 회피 시간 (시간) */
    const val RECENT_WORKOUT_HOURS = 24

    /** 프로필/설정 없을 때 기본 프로그램 타입 */
    const val DEFAULT_PROGRAM_TYPE = "FULL_BODY"
}
