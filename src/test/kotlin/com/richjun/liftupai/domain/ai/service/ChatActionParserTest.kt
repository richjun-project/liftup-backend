package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.chat.entity.ChatAction
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ChatActionParserTest {

    @Test
    fun `tags become actions and disappear from the text`() {
        val parsed = ChatActionParser.extract("오늘은 스쿼트부터 가자.\n\n[[START_TODAY_WORKOUT]]")
        assertThat(parsed.text).isEqualTo("오늘은 스쿼트부터 가자.")
        assertThat(parsed.actions).containsExactly(ChatAction.START_TODAY_WORKOUT)
    }

    @Test
    fun `at most two, unknown and server-only tags are ignored`() {
        val parsed = ChatActionParser.extract(
            "Log it. [[OPEN_MEAL_LOG]] [[SET_REMINDER]] [[DELETE_ACCOUNT]] [[OPEN_STATS]] [[OPEN_PLAN]]"
        )
        assertThat(parsed.actions).containsExactly(ChatAction.OPEN_MEAL_LOG, ChatAction.OPEN_STATS)
        assertThat(parsed.text).isEqualTo("Log it.")
    }

    @Test
    fun `plain replies are untouched`() {
        val parsed = ChatActionParser.extract("물 많이 마셔.")
        assertThat(parsed.text).isEqualTo("물 많이 마셔.")
        assertThat(parsed.actions).isEmpty()
    }
}
