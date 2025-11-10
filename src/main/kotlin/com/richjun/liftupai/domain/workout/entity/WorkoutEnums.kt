package com.richjun.liftupai.domain.workout.entity

enum class SessionStatus {
    PLANNED,
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    ABANDONED
}

enum class ExerciseCategory {
    CHEST,
    BACK,
    LEGS,
    SHOULDERS,
    ARMS,
    CORE,
    CARDIO,
    FULL_BODY
}

enum class Equipment {
    BARBELL,
    DUMBBELL,
    MACHINE,
    CABLE,
    BODYWEIGHT,
    RESISTANCE_BAND,
    KETTLEBELL,
    OTHER
}

enum class MuscleGroup {
    // Flutter 프론트엔드와 일치하는 16개 근육 그룹
    // /lib/core/utils/workout_translations.dart의 muscleGroups 맵과 동일
    CHEST,      // 가슴
    BACK,       // 등
    SHOULDERS,  // 어깨
    BICEPS,     // 이두근
    TRICEPS,    // 삼두근
    LEGS,       // 다리
    CORE,       // 코어
    ABS,        // 복근
    GLUTES,     // 둔근
    CALVES,     // 종아리
    FOREARMS,   // 전완근
    NECK,       // 목
    QUADRICEPS, // 대퇴사두근
    HAMSTRINGS, // 대퇴이두근 (햄스트링)
    LATS,       // 광배근
    TRAPS       // 승모근
}

/**
 * 운동 추천 등급
 *
 * 데이터는 모두 보존되며, 추천 시스템에서만 필터링에 사용됨
 *
 * 추천 정책 (총 567개 운동):
 * - 일반 추천: ESSENTIAL(57개) + STANDARD(222개) = 279개
 * - 제외: ADVANCED(112개) + SPECIALIZED(176개) = 288개
 * - ADVANCED/SPECIALIZED는 검색 시에만 노출, 자동 추천에서는 제외
 */
enum class RecommendationTier {
    /**
     * ESSENTIAL: 필수 기본 운동 (목표: 30-40개)
     * - Big 3 (스쿼트, 데드리프트, 벤치프레스)
     * - 초보자 필수 복합 운동
     * - 초보자 이상 모든 사용자에게 추천
     *
     * 예시:
     * - 백 스쿼트, 데드리프트, 벤치프레스
     * - 오버헤드 프레스, 바벨 로우, 풀업
     * - 레그 프레스, 런지, 플랭크
     */
    ESSENTIAL,

    /**
     * STANDARD: 일반적인 헬스장 운동 (목표: 80-100개)
     * - 대부분의 헬스장에서 흔히 하는 운동
     * - 기본 운동의 일반적인 변형
     * - 중급자 이상 사용자에게 추천
     *
     * 예시:
     * - 인클라인 벤치프레스, 디클라인 벤치프레스
     * - 프론트 스쿼트, 루마니안 데드리프트
     * - 덤벨 플라이, 레그 익스텐션, 케이블 로우
     */
    STANDARD,

    /**
     * ADVANCED: 흔하지 않은 변형 운동 (목표: 50-70개)
     * - 특정 목적이나 상황에서 사용하는 특수 변형
     * - 고급 트레이닝 기법
     * - 추천에서 제외, 검색 시에만 노출
     *
     * 예시:
     * - 클로즈그립 벤치프레스, 불가리안 스플릿 스쿼트
     * - 페이스풀, 컨센트레이션 컬
     * - 클린, 스내치 (올림픽 리프팅)
     */
    ADVANCED,

    /**
     * SPECIALIZED: 거의 안 쓰는 전문/특수 운동 (나머지)
     * - 매우 특수한 변형, 비표준 동작
     * - 희귀 장비 필요 (슬레드, GHD 등)
     * - 부상 위험이 높거나 실용성 낮음
     * - 추천에서 제외, 검색 시에만 노출
     *
     * 예시:
     * - 길로틴 프레스, 제르처 스쿼트, 시일 로우
     * - 비하인드 넥 프레스, 드래그 컬, JM 프레스
     * - 슬레드 푸시, 타이어 플립
     */
    SPECIALIZED
}