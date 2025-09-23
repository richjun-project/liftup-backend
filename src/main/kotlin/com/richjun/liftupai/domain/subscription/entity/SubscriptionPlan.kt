package com.richjun.liftupai.domain.subscription.entity

enum class SubscriptionPlan(
    val displayName: String,
    val price: Int,
    val features: List<String>
) {
    FREE(
        displayName = "무료",
        price = 0,
        features = listOf(
            "기본 운동 기록",
            "AI 추천 (하루 5회)",
            "기본 통계"
        )
    ),
    BASIC(
        displayName = "베이직",
        price = 9900,
        features = listOf(
            "무제한 운동 기록",
            "AI 추천 (하루 50회)",
            "상세 통계",
            "운동 프로그램 생성",
            "영양 분석"
        )
    ),
    PREMIUM(
        displayName = "프리미엄",
        price = 19900,
        features = listOf(
            "베이직 모든 기능",
            "무제한 AI 추천",
            "개인 맞춤 PT",
            "실시간 자세 분석",
            "운동 파트너 매칭",
            "오프라인 동기화",
            "우선 지원"
        )
    )
}

enum class SubscriptionStatus {
    ACTIVE,
    EXPIRED,
    CANCELLED,
    PENDING,
    TRIAL
}