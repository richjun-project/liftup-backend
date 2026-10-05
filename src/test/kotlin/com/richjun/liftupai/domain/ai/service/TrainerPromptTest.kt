package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.user.entity.PTStyle
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock

class TrainerPromptTest {

    private val service = GeminiAIService(
        mock(com.fasterxml.jackson.databind.ObjectMapper::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserProfileRepository::class.java),
        mock(com.richjun.liftupai.domain.user.repository.UserSettingsRepository::class.java),
        mock(com.richjun.liftupai.domain.nutrition.service.NutritionContextService::class.java),
    )

    @Test
    fun `the trainer knows today's session and how to attach buttons`() {
        val context = "## TRAINING CONTEXT (real data from the app)\n- Active plan: \"상하체 분할\". Today's session = Day 1 \"상체 A\""
        val p = service.buildChatPrompt("오늘 뭐 해?", "민수", PTStyle.SPARTAN, "ko", null, trainerContext = context)

        assertThat(p).contains("## TRAINING CONTEXT").contains("상체 A")
        assertThat(p).contains("## ACTION BUTTONS").contains("[[START_TODAY_WORKOUT]]").contains("[[OPEN_MEAL_LOG]]")
        // 여전히 데이터를 바꿨다고 말하면 안 된다
        assertThat(p).contains("Never claim the app changed or started anything")
        // 운동 정보는 대화 기록보다 앞에 온다(순서 = 우선순위)
        assertThat(p.indexOf("## TRAINING CONTEXT")).isLessThan(p.indexOf("## ACTION BUTTONS"))
    }

    @Test
    fun `without context the prompt still offers buttons but no training block`() {
        val p = service.buildChatPrompt("안녕", "민수", PTStyle.BURNOUT, "en", null)
        assertThat(p).doesNotContain("## TRAINING CONTEXT").contains("## ACTION BUTTONS")
    }

    @Test
    fun `no start button when the user says something hurts or shows a red flag`() {
        val start = listOf(com.richjun.liftupai.domain.chat.entity.ChatAction.START_TODAY_WORKOUT,
            com.richjun.liftupai.domain.chat.entity.ChatAction.OPEN_PLAN)
        val none = ChatSafetyService.RiskProfile()
        for (msg in listOf("膝が痛いんだけど今日どうしよう", "어깨가 아파요", "my knee hurts today", "허리 다쳤어")) {
            assertThat(service.safeActions(start, none, msg)).`as`(msg)
                .containsExactly(com.richjun.liftupai.domain.chat.entity.ChatAction.OPEN_PLAN)
        }
        assertThat(service.safeActions(start, none, "오늘 운동 뭐 해?")).isEqualTo(start)
        assertThat(service.safeActions(start, ChatSafetyService.RiskProfile(redFlagSymptoms = true), "숨이 차요"))
            .containsExactly(com.richjun.liftupai.domain.chat.entity.ChatAction.OPEN_PLAN)
    }
}
