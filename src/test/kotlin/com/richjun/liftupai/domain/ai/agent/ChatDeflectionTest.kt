package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.richjun.liftupai.domain.ai.service.ChatComputedFacts
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.ai.service.ChatSafetyService.Violation
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.analytics.AppEventService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.PTStyle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 떠넘기기 금지(2026-11-14 coach-autopilot-r1).
 * 296 은 "오늘 풀 데이"를 말했다가 설정 화면으로, 304 는 "器具はありません"을 말했다가 "플랜 설정에서"로 보내졌다.
 * 트레이너가 도구로 할 수 있는 일을 화면으로 떠넘기면 위반이고, 도구를 쓸 수 있는 상태로 다시 생각하게 한다.
 */
class ChatDeflectionTest {
    private val safety = ChatSafetyService()
    private fun risk(locale: String) = ChatSafetyService.RiskProfile(locale = locale)

    @Test
    @DisplayName("도구로 할 수 있는 요청(장비·오늘 Day·운동 시간)을 설정 화면으로 보내면 위반 — ko·ja·en")
    fun detectsDeflection() {
        val cases = listOf(
            Triple("ko", "집에 기구가 하나도 없어요", "플랜 설정에서 맨몸 플랜으로 바꿔 주세요."),
            Triple("ja", "器具はありません", "プラン設定から自重のプランを選んでください。"),
            Triple("en", "today is pull day, can I do that instead?", "You can change which day you train in Settings."),
            Triple("ko", "운동 시간 저녁 8시로 바꿔줘", "설정 화면에서 운동 시간을 변경할 수 있어요."),
            Triple("ko", "스쿼트 대신 다른 운동으로 바꿔 줘", "플랜 화면에서 운동을 교체해 보세요."),
        )
        cases.forEach { (locale, msg, reply) ->
            assertTrue(Violation.DEFLECTION_TO_SETTINGS in safety.validate(reply, risk(locale), userMessage = msg), "$msg → $reply")
        }
    }

    @Test
    @DisplayName("진짜 설정 일(언어·구독·계정 삭제), 도구로 이미 한 답, 단발 경로(사용자 메시지 없음)는 위반이 아니다")
    fun noFalsePositives() {
        val ok = listOf(
            Triple("ko", "앱 언어를 영어로 바꾸려면?", "설정에서 언어를 바꿀 수 있어요."),
            Triple("ko", "구독 취소는 어떻게 해?", "설정에서 구독 관리로 들어가면 취소할 수 있어요."),
            Triple("ja", "アカウントを削除したい", "設定からアカウントを削除できます。"),
            Triple("en", "how do I turn off notifications?", "You can turn them off in your phone's settings."),
            Triple("ko", "집에 기구가 하나도 없어요", "맨몸으로 할 수 있게 오늘 운동을 바꿨어요. 스쿼트·푸시업부터 가요."),
        )
        ok.forEach { (locale, msg, reply) ->
            assertFalse(Violation.DEFLECTION_TO_SETTINGS in safety.validate(reply, risk(locale), userMessage = msg), "$msg → $reply")
        }
        // 단발 경로는 도구가 없어 설정 안내가 정답 — 사용자 메시지를 넘기지 않으면 검사하지 않는다
        assertFalse(Violation.DEFLECTION_TO_SETTINGS in safety.validate("플랜 설정에서 맨몸 플랜으로 바꿔 주세요.", risk("ko")))
    }

    @Test
    @DisplayName("거짓 약속 재작성 지시는 더 이상 플랜 화면으로 보내라고 하지 않고, 도구를 쓰라고 한다")
    fun falsePromiseInstructionNoLongerDeflects() {
        val block = safety.rejectionBlock(listOf(Violation.FALSE_PROMISE_OF_PLAN_CHANGE), risk("ko"))
        assertFalse(block.contains("point to the plan screen"), block)
        assertTrue(block.contains("call it now"), block)
        val d = safety.rejectionBlock(listOf(Violation.DEFLECTION_TO_SETTINGS), risk("ko"))
        assertTrue(d.contains("set_available_equipment") && d.contains("set_today_workout_day"), d)
    }

    // ── 에이전트 루프: 떠넘기면 도구를 쓸 수 있는 상태로 다시 ─────────────────────────

    private val mapper = ObjectMapper()
    private val user = User(id = 304L, email = "x@example.com", nickname = "T")
    private val gemini = mockk<GeminiAIService>(relaxed = true)
    private val memories = mockk<CoachMemoryRepository>()
    private val events = mockk<AppEventService>(relaxed = true)

    private inner class ScriptedModel(vararg responses: String) : AgentModel {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<ObjectNode>()
        override fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult {
            requests += body.deepCopy()
            return AgentModelResult.Success(mapper.readTree(queue.removeFirstOrNull() ?: text("…")), 1000)
        }
    }

    private fun text(t: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":${mapper.writeValueAsString(t)}}]}}]}"""
    private fun call(name: String, args: String) =
        """{"candidates":[{"content":{"role":"model","parts":[{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"s"}]}}]}"""

    private class FakeTool(override val name: String, override val mutating: Boolean = false, val body: () -> Map<String, Any?>) : CoachTool {
        var calls = 0
        override fun declaration() = mapOf("name" to name, "description" to name, "parameters" to mapOf("type" to "OBJECT", "properties" to emptyMap<String, Any>()))
        override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> { calls++; return body() }
    }

    private val equipment = FakeTool("set_available_equipment", mutating = true) { mapOf("ok" to true, "changed" to true, "plan_fitted" to true) }

    @BeforeEach
    fun setUp() {
        every { memories.findByUserIdOrderByUpdatedAtDesc(any()) } returns emptyList()
        every { gemini.prepareChat(any(), any(), any(), any()) } returns GeminiAIService.PreparedChat(
            locale = "ja", ptStyle = PTStyle.SPARTAN, userProfile = null, nutritionContext = null,
            risk = ChatSafetyService.RiskProfile(locale = "ja"), facts = ChatComputedFacts.Facts(emptyList()),
            images = emptyList(), imageNote = "", timeZone = "Asia/Tokyo",
        )
        every { gemini.buildChatPrompt(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "PROMPT"
        every { gemini.chatSafety } returns safety
        every { gemini.maskNickname(any(), any()) } answers { firstArg() }
        every { gemini.safeActions(any(), any(), any()) } answers { firstArg() }
        every { gemini.generateReply(any(), any(), any(), any(), any()) } returns GeminiAIService.ChatReply("legacy answer")
    }

    private fun service(model: AgentModel) =
        CoachAgentService(gemini, model, CoachToolbox(listOf(equipment)), memories, mapper, appEvents = events).also { it.clock = { 0L } }

    @Test
    @DisplayName("304 재현: '器具はありません'에 설정으로 보내면 도구를 켠 채 다시 생각하게 하고, 장비 도구를 부른 답이 나간다(이벤트 fixed=true)")
    fun deflectionReentersToolLoop() {
        val model = ScriptedModel(
            text("プラン設定から自重のプランを選んでください。"),
            call("set_available_equipment", """{"equipment":["BODYWEIGHT"]}"""),
            text("器具なしで回せるように今日のメニューを自重に変えた。スクワットからいくぞ。[[START_TODAY_WORKOUT]]"),
        )
        val r = service(model).reply(user, "器具はありません", emptyList(), trainerContext = null)
        assertEquals("agent", r.path)
        assertEquals(1, equipment.calls)
        assertTrue(r.text.startsWith("器具なしで"), r.text)
        // 재생성 호출은 도구를 끄지 않는다(AUTO)
        assertEquals("AUTO", model.requests[1].at("/toolConfig/functionCallingConfig/mode").asText())
        verify { events.recordServer(304L, "coach_deflection", match { it["topic"] == "equipment" && it["fixed"] == true }) }
    }

    @Test
    @DisplayName("다시 생각해도 떠넘기면(또는 오탐이면) 안전 대체 문구로 덮지 않고 그 답을 보내며, 이벤트는 fixed=false")
    fun persistentDeflectionIsNotReplacedByFallback() {
        val model = ScriptedModel(
            text("プラン設定から自重のプランを選んでください。"),
            text("プラン設定から自重のプランを選んでください。"),
        )
        val r = service(model).reply(user, "器具はありません", emptyList(), trainerContext = null)
        assertEquals("agent", r.path)
        assertTrue(r.text.startsWith("プラン設定から"), r.text)
        verify { events.recordServer(304L, "coach_deflection", match { it["fixed"] == false }) }
    }

    @Test
    @DisplayName("도구가 없는 요청(주 운동 일수)에 설정을 안내하면 위반은 아니지만 '없는 능력' 이벤트를 남긴다")
    fun missingCapabilityIsRecorded() {
        val model = ScriptedModel(text("週の回数はプラン設定から変更できます。"))
        val r = service(model).reply(user, "週4回に減らしたい", emptyList(), trainerContext = null)
        assertEquals("agent", r.path)
        verify { events.recordServer(304L, "coach_missing_capability", match { it["kind"] == "settings_pointer" }) }
        verify(exactly = 0) { events.recordServer(any(), "coach_deflection", any()) }
    }
}
