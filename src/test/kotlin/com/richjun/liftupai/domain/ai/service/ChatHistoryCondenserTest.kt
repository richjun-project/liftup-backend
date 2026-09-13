package com.richjun.liftupai.domain.ai.service

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class ChatHistoryCondenserTest {

    @Test
    @DisplayName("최근 4턴은 원문, 그 이전은 한 줄 요약")
    fun splitsRecentAndOlder() {
        val history = (1..7).map { "질문$it" to "답변$it. 두 번째 문장$it." }
        val c = ChatHistoryCondenser.condense(history)
        assertThat(c.recent).hasSize(4)
        assertThat(c.recent.first().first).isEqualTo("질문4")
        assertThat(c.summaryLines).hasSize(3)
        assertThat(c.summaryLines.first()).isEqualTo("User asked: \"질문1\" → You answered: \"답변1.\"")
    }

    @Test
    @DisplayName("긴 루틴 답변은 400자에서 잘리지 않고 800자까지 남는다 (후속 질문 '그거 몇 세트?' 대응)")
    fun keepsLongRoutine() {
        val routine = "1. 벤치프레스 4세트 8회\n".repeat(30) // ~660자
        val c = ChatHistoryCondenser.condense(listOf("루틴 짜줘" to routine))
        assertThat(c.recent.single().second.length).isGreaterThan(400)
        assertThat(c.recent.single().second).contains("4세트 8회")
    }

    @Test
    @DisplayName("800자를 넘으면 앞부분을 보존하고 말줄임")
    fun truncatesTail() {
        val long = "a".repeat(1000)
        val c = ChatHistoryCondenser.condense(listOf("q" to long))
        assertThat(c.recent.single().second).hasSize(800).endsWith("…")
    }

    @Test
    @DisplayName("프롬프트 블록에는 후속 질문 규칙이 들어가고, 히스토리가 없으면 비어 있다")
    fun promptBlock() {
        assertThat(ChatHistoryCondenser.promptBlock(ChatHistoryCondenser.condense(emptyList()))).isEmpty()
        val block = ChatHistoryCondenser.promptBlock(ChatHistoryCondenser.condense(listOf("q" to "a")))
        assertThat(block).contains("Conversation so far").contains("never ask what they mean")
    }
}
