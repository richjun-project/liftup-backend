package com.richjun.liftupai.domain.progress.calc

/** 레벨·XP 규칙 상수 — 규칙을 바꿀 땐 여기만 고친다. */
object XpRules {
    const val SET_BASE = 10
    const val MAX_INTENSITY = 10
    const val WORKOUT_COMPLETE = 50
    const val PERSONAL_RECORD = 100
    const val MAX_PR_PER_SESSION = 3
    const val WEEKLY_GOAL = 300
    const val MEAL = 5
    const val MEAL_DAILY_MAX = 20
    const val DAILY_CAP = 600
    const val DEFAULT_BODYWEIGHT_KG = 70.0
    const val BODYWEIGHT_EXERCISE_FACTOR = 0.5
    const val DEFAULT_WEEKLY_GOAL = 3
    const val ACTIVE_WEEK_MIN_SESSIONS = 2
    const val DEFAULT_ZONE = "Asia/Seoul"

    /** (연속 활성 주 수 이상, 배율) — 큰 값부터 */
    val STREAK_MULTIPLIERS: List<Pair<Int, Double>> = listOf(8 to 1.3, 4 to 1.2, 2 to 1.1)

    const val LEVEL_COEFFICIENT = 22.0
    const val LEVEL_EXPONENT = 1.9

    /** (시작 레벨, 클래스 키) — 큰 값부터 */
    val CLASSES: List<Pair<Int, String>> = listOf(65 to "legend", 40 to "champion", 20 to "warrior", 8 to "apprentice", 1 to "novice")
    val EVOLUTION_LEVELS: List<Int> = listOf(8, 20, 40, 65)
}
