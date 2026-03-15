package com.richjun.liftupai.domain.workout.entity

enum class SplitType {
    FULL_BODY,
    PPL,
    UPPER_LOWER,
    BRO_SPLIT,
    PPLUL
}

enum class ProgressionModel {
    LINEAR,       // BEGINNER: 매 세션 +2.5kg(상체) / +5kg(하체)
    UNDULATING,   // INTERMEDIATE: Heavy/Medium/Light 순환
    BLOCK         // ADVANCED: 축적기 → 강화기 → 실현기
}

enum class SubstitutionReason {
    EQUIPMENT,
    INJURY,
    PREFERENCE,
    EQUIVALENT
}

enum class InjurySeverity {
    MILD,
    MODERATE,
    SEVERE,
    ALL
}

enum class EnrollmentStatus {
    ACTIVE,
    PAUSED,
    COMPLETED,
    ABANDONED
}
