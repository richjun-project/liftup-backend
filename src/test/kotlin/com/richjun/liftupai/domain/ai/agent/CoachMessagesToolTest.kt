package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.notification.dto.UpdateNotificationSettingsRequest
import com.richjun.liftupai.domain.notification.service.NotificationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** 트레이너 메시지 끄기·켜기 도구(2026-11-14) — PT 스케줄러가 읽는 NotificationSettings.aiMessages 를 바꾼다 */
class CoachMessagesToolTest {
    private val mapper = ObjectMapper()
    private val notifications = mockk<NotificationService>(relaxed = true)
    private val tool = CoachMessagesTool(notifications)
    private val ctx = CoachToolContext(User(id = 9L, email = "a@b.c", nickname = "n"), "ko", ZoneId.of("Asia/Seoul"), ChatSafetyService.RiskProfile(locale = "ko"))

    private fun current(on: Boolean) {
        every { notifications.getNotificationSettings(9L) } returns mockk { every { aiMessages } returns on }
    }

    @Test
    @DisplayName("켜져 있을 때 끄면 설정을 바꾸고 changed=true, 이미 같으면 바꾸지 않고 changed=false")
    fun togglesAiMessages() {
        current(true)
        val r = tool.run(ctx, mapper.readTree("""{"enabled":false}"""))
        assertEquals(true, r["changed"]); assertEquals("off", r["coach_messages"])
        verify { notifications.updateNotificationSettings(9L, UpdateNotificationSettingsRequest(aiMessages = false)) }

        current(false)
        val same = tool.run(ctx, mapper.readTree("""{"enabled":false}"""))
        assertEquals(false, same["changed"])
        verify(exactly = 1) { notifications.updateNotificationSettings(any(), any()) }
    }

    @Test
    @DisplayName("enabled 가 없거나 불리언이 아니면 인자 오류 — 아무것도 바꾸지 않는다")
    fun rejectsBadArgs() {
        current(true)
        assertThrows(ToolArgumentException::class.java) { tool.run(ctx, mapper.readTree("{}")) }
        assertThrows(ToolArgumentException::class.java) { tool.run(ctx, mapper.readTree("""{"enabled":"no"}""")) }
        verify(exactly = 0) { notifications.updateNotificationSettings(any(), any()) }
    }

    @Test
    @DisplayName("설정을 바꾸는 도구로 등록돼 '꺼 드렸어요'가 거짓 약속으로 막히지 않고, 선언은 BOOLEAN 인자 하나")
    fun registeredAsSettingsChanging() {
        assertTrue("set_coach_messages" in CoachAgentService.SETTINGS_CHANGING_TOOLS)
        assertTrue(tool.mutating)
        @Suppress("UNCHECKED_CAST")
        val params = tool.declaration()["parameters"] as Map<String, Any>
        assertEquals(listOf("enabled"), params["required"])
    }
}
