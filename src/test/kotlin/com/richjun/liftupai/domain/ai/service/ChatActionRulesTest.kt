package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.chat.entity.ChatAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChatActionRulesTest {

    @Test
    fun `asking what to do today adds the start button in every language`() {
        for (q in listOf("오늘 운동 뭐 해?", "운동 추천해줘", "What should I do today?", "今日のトレーニングは？")) {
            assertThat(ChatActionRules.finalize(emptyList(), q, hasActivePlan = true))
                .`as`(q).containsExactly(ChatAction.START_TODAY_WORKOUT)
        }
    }

    @Test
    fun `casual talk gets no button`() {
        assertThat(ChatActionRules.finalize(emptyList(), "안녕! 반가워", hasActivePlan = true)).isEmpty()
    }

    @Test
    fun `without a plan the start button becomes the plan button`() {
        assertThat(ChatActionRules.finalize(listOf(ChatAction.START_TODAY_WORKOUT), "오늘 운동 뭐 해?", hasActivePlan = false))
            .containsExactly(ChatAction.OPEN_PLAN)
    }

    @Test
    fun `model buttons are kept and capped at two`() {
        assertThat(
            ChatActionRules.finalize(listOf(ChatAction.OPEN_MEAL_LOG, ChatAction.OPEN_STATS), "오늘 운동 뭐 해?", hasActivePlan = true)
        ).containsExactly(ChatAction.START_TODAY_WORKOUT, ChatAction.OPEN_MEAL_LOG)
    }
}
