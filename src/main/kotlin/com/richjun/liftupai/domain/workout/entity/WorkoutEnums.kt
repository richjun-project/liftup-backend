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