package com.richjun.liftupai.domain.user.entity

enum class ExperienceLevel {
    BEGINNER,
    NOVICE,
    INTERMEDIATE,
    ADVANCED,
    EXPERT
}

enum class FitnessGoal {
    MUSCLE_GAIN,
    WEIGHT_LOSS,
    STRENGTH,
    ENDURANCE,
    GENERAL_FITNESS,
    ATHLETIC_PERFORMANCE
}

enum class PTStyle {
    SPARTAN,           // 스파르타 - 강한 동기부여
    BURNOUT,           // 3년차 번아웃 김PT - 모든 변명 다 들어본 현타 온 트레이너
    GAME_MASTER,       // 게임 마스터 레벨업 - 모든 운동을 RPG로 변환하는 덕후 트레이너
    INFLUENCER,        // 인플루언서 워너비 예나쌤 - 필라테스와 요가에 진심인 감성 트레이너
    HIP_HOP,           // 힙합 PT 스웨거 - 모든 걸 힙합 가사처럼 말하는 스타일
    RETIRED_TEACHER,   // 은퇴한 체육선생님 박선생 - 옛날 얘기와 라떼 썰 풀면서 운동
    OFFICE_MASTER,     // 회식 마스터 이과장 - 직장인의 아픔을 100% 이해하는 아저씨
    LA_KOREAN,         // LA 교포 PT 제이슨 - 영어 섞어쓰며 미국식 텐션으로 밀어붙이는 스타일
    BUSAN_VETERAN,     // 부산 선수 출신 동수형 - 거친 부산 사투리로 팩트폭격하는 현실적인 트레이너
    SOLDIER            // 갓 전입온 일병 김일병 - 열정은 있지만 서툴고 군대식 습관이 남은 어설픈 PT
}

enum class Gender {
    MALE,
    FEMALE,
    OTHER
}