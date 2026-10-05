package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.chat.entity.ChatAction

/**
 * 모델이 버튼을 빠뜨리거나 쓸 수 없는 버튼을 붙였을 때의 보정.
 * - "오늘 뭐 해?" 처럼 오늘 운동을 묻는 말에는 오늘 운동 버튼을 붙인다(플랜이 있을 때만).
 * - 플랜이 없으면 오늘 운동 버튼 대신 플랜 버튼을 준다.
 */
object ChatActionRules {
    private val TODAY_WORKOUT_INTENT = listOf(
        Regex("""(오늘|지금|이따).{0,8}(운동|루틴|뭐\s*(하|해))"""),
        Regex("""운동\s*(추천|시작|뭐\s*(하|해))|루틴\s*추천|무슨\s*운동"""),
        Regex("""(?i)what (should|do) i (do|train)|today'?s workout|workout (for )?today|start (my |a )?workout|what to train"""),
        Regex("""今日.{0,8}(トレーニング|運動|メニュー|筋トレ)|何を(すれば|やれば)|(トレーニング|筋トレ).{0,4}(始め|おすすめ)"""),
    )

    fun isTodayWorkoutQuestion(message: String): Boolean = TODAY_WORKOUT_INTENT.any { it.containsMatchIn(message) }

    fun finalize(modelActions: List<ChatAction>, message: String, hasActivePlan: Boolean): List<ChatAction> {
        var actions = modelActions
        if (hasActivePlan && ChatAction.START_TODAY_WORKOUT !in actions && isTodayWorkoutQuestion(message)) {
            actions = listOf(ChatAction.START_TODAY_WORKOUT) + actions
        }
        if (!hasActivePlan && ChatAction.START_TODAY_WORKOUT in actions) {
            actions = actions.map { if (it == ChatAction.START_TODAY_WORKOUT) ChatAction.OPEN_PLAN else it }
        }
        return actions.distinct().take(2)
    }
}
