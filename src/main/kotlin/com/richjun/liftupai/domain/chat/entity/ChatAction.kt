package com.richjun.liftupai.domain.chat.entity

/**
 * AI 트레이너 메시지 아래에 붙는 행동 버튼. 앱은 이름으로 동작을 고르고, 모르는 이름은 무시한다.
 *
 * 2026-10: 채팅이 앱 기능과 끊겨 있었다 — 트레이너가 오늘 운동을 권해도 시작할 버튼이 없었고,
 * 프롬프트는 "너는 아무것도 할 수 없다"고만 말했다. 트레이너의 말이 곧바로 행동으로 이어지게 한다.
 */
enum class ChatAction {
    /** 오늘 플랜 운동 미리보기 → 시작 */
    START_TODAY_WORKOUT,
    /** 운동 알림 시각을 보여 주고 알림 권한을 묻는다 */
    SET_REMINDER,
    /** 사진으로 식단 기록 */
    OPEN_MEAL_LOG,
    /** 플랜 보기·바꾸기 */
    OPEN_PLAN,
    /** 통계 탭(Pro) */
    OPEN_STATS;

    companion object {
        /** 모델이 답에 붙일 수 있는 버튼. SET_REMINDER 는 서버가 상황을 보고 붙인다. */
        val MODEL_ALLOWED: Set<ChatAction> = setOf(START_TODAY_WORKOUT, OPEN_MEAL_LOG, OPEN_PLAN, OPEN_STATS)

        fun parseList(raw: String?): List<ChatAction> =
            raw.orEmpty().split(',').mapNotNull { name -> entries.firstOrNull { it.name == name.trim() } }.distinct()

        fun join(actions: List<ChatAction>): String? =
            actions.distinct().takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
    }
}
