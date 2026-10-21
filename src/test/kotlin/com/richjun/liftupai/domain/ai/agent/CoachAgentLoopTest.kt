package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.richjun.liftupai.domain.ai.service.ChatComputedFacts
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.ai.service.GeminiAIService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.chat.entity.ChatAction
import com.richjun.liftupai.domain.user.entity.PTStyle
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * 에이전트 루프의 규칙을 가짜 모델로 고정한다(실제 Gemini 없이).
 * 모델 응답 모양은 Gemini v1beta generateContent 그대로: candidates[0].content.parts[] 에 text 또는 functionCall(+thoughtSignature).
 */
class CoachAgentLoopTest {

    private val mapper = ObjectMapper()
    private val user = User(id = 7L, email = "a@example.com", nickname = "はると")
    private val gemini = mockk<GeminiAIService>(relaxed = true)
    private val memories = mockk<CoachMemoryRepository>()
    private val safety = ChatSafetyService()

    /** 미리 적어 둔 응답을 차례로 돌려주고, 받은 요청을 남긴다 */
    private inner class ScriptedModel(vararg responses: Any) : AgentModel {
        val queue = ArrayDeque(responses.toList())
        val requests = mutableListOf<ObjectNode>()
        val timeouts = mutableListOf<Long>()
        var onCall: () -> Unit = {}
        override fun generate(body: ObjectNode, timeoutMs: Long): AgentModelResult {
            requests += body.deepCopy()
            timeouts += timeoutMs
            onCall()
            return when (val next = queue.removeFirstOrNull() ?: text("…")) {
                is AgentModelResult -> next
                else -> AgentModelResult.Success(mapper.readTree(next as String), 1500)
            }
        }
    }

    private fun text(t: String) = """{"candidates":[{"content":{"role":"model","parts":[{"text":${mapper.writeValueAsString(t)}}]}}]}"""
    private fun calls(vararg c: Pair<String, String>, signature: String? = "sig-abc") =
        """{"candidates":[{"content":{"role":"model","parts":[${c.mapIndexed { i, (name, args) ->
            """{"functionCall":{"name":"$name","args":$args}${if (i == 0 && signature != null) ""","thoughtSignature":"$signature"""" else ""}}"""
        }.joinToString(",")}]}}]}"""

    private class FakeTool(override val name: String, override val mutating: Boolean = false, val body: (JsonNode) -> Map<String, Any?>) : CoachTool {
        val received = mutableListOf<JsonNode>()
        override fun declaration() = mapOf("name" to name, "description" to name, "parameters" to mapOf("type" to "OBJECT", "properties" to emptyMap<String, Any>()))
        override fun run(ctx: CoachToolContext, args: JsonNode): Map<String, Any?> { received.add(args); return body(args) }
    }

    private val recent = FakeTool("get_recent_workouts") { mapOf("ok" to true, "sessions" to listOf(mapOf("date" to "2026-10-18", "exercises" to "bench 60x8"))) }
    private val today = FakeTool("get_today_workout") { mapOf("ok" to true, "exercises" to listOf(mapOf("exercise_id" to 11, "name" to "スクワット"))) }
    private val swap = FakeTool("swap_exercise_in_plan", mutating = true) { mapOf("ok" to true, "changed" to true, "from" to "スクワット", "to" to "レッグプレス") }
    private val boom = FakeTool("get_recovery_status") { throw IllegalStateException("db down") }

    private fun service(model: AgentModel, clock: () -> Long = { 0L }) =
        CoachAgentService(gemini, model, CoachToolbox(listOf(recent, today, swap, boom)), memories, mapper).also {
            it.clock = clock
        }

    @BeforeEach
    fun setUp() {
        every { memories.findByUserIdOrderByUpdatedAtDesc(7L) } returns emptyList()
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
        every { gemini.getFallbackResponse(any(), any(), any()) } returns "fallback answer"
    }

    private fun run(model: AgentModel, clock: () -> Long = { 0L }, message: String = "先週のベンチはどうだった？") =
        service(model, clock).reply(user, message, emptyList(), trainerContext = null)

    @Test
    fun `도구가 필요 없으면 한 번에 답하고 도구 선언·AUTO 모드를 보낸다`() {
        val model = ScriptedModel(text("こんにちは、はると！今日もやるぞ。[[START_TODAY_WORKOUT]]"))
        val r = run(model, message = "こんにちは")
        assertEquals("agent", r.path)
        assertEquals("こんにちは、はると！今日もやるぞ。", r.text)
        assertEquals(listOf(ChatAction.START_TODAY_WORKOUT), r.actions)
        assertTrue(r.tools.isEmpty())
        val req = model.requests.single()
        assertEquals("AUTO", req.at("/toolConfig/functionCallingConfig/mode").asText())
        val names = req.at("/tools/0/functionDeclarations").map { it.path("name").asText() }
        assertTrue(names.containsAll(listOf("get_recent_workouts", "swap_exercise_in_plan")), names.toString())
    }

    @Test
    fun `도구를 부르면 실행 결과를 돌려주고, 모델 턴은 서명까지 그대로 이어 붙인다`() {
        val model = ScriptedModel(calls("get_recent_workouts" to """{"days":7}"""), text("先週はベンチ60kgを8回。上出来だ。"))
        val r = run(model)
        assertEquals("先週はベンチ60kgを8回。上出来だ。", r.text)
        assertEquals(listOf(CoachAgentService.ToolUse("get_recent_workouts", ok = true, changed = false)), r.tools)
        assertEquals(7, recent.received.single().path("days").asInt())
        val second = model.requests[1].path("contents")
        assertEquals("model", second[1].path("role").asText())
        assertEquals("sig-abc", second[1].at("/parts/0/thoughtSignature").asText(), "Gemini 3 서명을 빼면 400")
        assertEquals("get_recent_workouts", second[2].at("/parts/0/functionResponse/name").asText())
        assertEquals("bench 60x8", second[2].at("/parts/0/functionResponse/response/result/sessions/0/exercises").asText())
    }

    @Test
    fun `한 번에 여러 도구를 부르면 모두 실행해 같은 순서로 돌려준다`() {
        val model = ScriptedModel(calls("get_today_workout" to "{}", "get_recent_workouts" to "{}"), text("今日はスクワットからだ。"))
        val r = run(model)
        assertEquals(listOf("get_today_workout", "get_recent_workouts"), r.tools.map { it.name })
        val parts = model.requests[1].path("contents")[2].path("parts")
        assertEquals(listOf("get_today_workout", "get_recent_workouts"), parts.map { it.at("/functionResponse/name").asText() })
    }

    @Test
    fun `모르는 도구·도구 오류는 실패 결과로 알려 주고 루프는 계속된다`() {
        val model = ScriptedModel(calls("delete_everything" to "{}", "get_recovery_status" to "{}"), text("回復データは今取れない。軽めにいこう。"))
        val r = run(model)
        assertEquals("agent", r.path)
        assertEquals(listOf(false, false), r.tools.map { it.ok })
        val parts = model.requests[1].path("contents")[2].path("parts")
        assertEquals("unknown_tool", parts[0].at("/functionResponse/response/result/error").asText())
        assertEquals("tool_failed", parts[1].at("/functionResponse/response/result/error").asText())
    }

    @Test
    fun `횟수 상한에 닿으면 마지막 호출은 도구를 끄고 답을 받는다`() {
        val model = ScriptedModel(
            calls("get_recent_workouts" to "{}"), calls("get_recent_workouts" to "{}"), calls("get_recent_workouts" to "{}"),
            calls("get_recent_workouts" to "{}"), text("まとめるとこうだ。"),
        )
        val r = run(model)
        assertEquals("まとめるとこうだ。", r.text)
        assertEquals(5, model.requests.size)
        assertEquals("NONE", model.requests.last().at("/toolConfig/functionCallingConfig/mode").asText())
        assertEquals("AUTO", model.requests[3].at("/toolConfig/functionCallingConfig/mode").asText())
    }

    @Test
    fun `시간 예산을 넘기면 다음 호출에서 도구를 끈다`() {
        var now = 0L
        val model = ScriptedModel(calls("get_recent_workouts" to "{}"), text("急いで答える。"))
        model.onCall = { now += 14_000 }
        val r = run(model, clock = { now })
        assertEquals("急いで答える。", r.text)
        assertEquals("NONE", model.requests[1].at("/toolConfig/functionCallingConfig/mode").asText())
    }

    @Test
    fun `모델 호출이 실패하면 단발 경로로 답한다(바뀐 기록은 남긴다)`() {
        val model = ScriptedModel(calls("get_recent_workouts" to "{}"), AgentModelResult.Failure(500, "boom", 1000))
        val r = run(model)
        assertEquals("legacy", r.path)
        assertEquals("legacy answer", r.text)
        assertEquals(listOf("get_recent_workouts"), r.tools.map { it.name })
    }

    @Test
    fun `남은 시간이 없을 때 실패하면 단발 호출 없이 대체 문구`() {
        var now = 0L
        val model = ScriptedModel(AgentModelResult.Failure(null, "timeout", 20_000))
        model.onCall = { now = 19_000 }
        val r = run(model, clock = { now })
        assertEquals("fallback", r.path)
        assertEquals("fallback answer", r.text)
        verify(exactly = 0) { gemini.generateReply(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `안전 위반(언어 불일치)이면 위반 사유를 붙여 한 번 다시 쓰게 한다`() {
        val model = ScriptedModel(text("Great job on your bench press last week, keep going!"), text("先週のベンチ、よくやった。続けろ。"))
        val r = run(model)
        assertEquals("先週のベンチ、よくやった。続けろ。", r.text)
        assertEquals(2, model.requests.size)
        assertEquals("NONE", model.requests[1].at("/toolConfig/functionCallingConfig/mode").asText())
    }

    @Test
    fun `변경 도구가 성공했으면 "바꿨어요"는 거짓 약속으로 막지 않는다 — 실패했으면 막는다`() {
        val ok = ScriptedModel(calls("swap_exercise_in_plan" to """{"from_exercise_id":11,"to_exercise_id":22}"""), text("I've swapped your plan: squats are now leg presses."))
        every { gemini.prepareChat(any(), any(), any(), any()) } returns GeminiAIService.PreparedChat(
            "en", PTStyle.SPARTAN, null, null, ChatSafetyService.RiskProfile(locale = "en"), ChatComputedFacts.Facts(emptyList()), emptyList(), "", null,
        )
        val r = run(ok, message = "swap squats for leg press please")
        assertEquals("agent", r.path)
        assertTrue(r.tools.single().changed)
        assertTrue(r.text.startsWith("I've swapped your plan"))

        // 같은 주장을 도구 없이 하면 재작성 → 그래도 같으면 안전 대체 문구
        val lie = ScriptedModel(text("I've changed your plan: squats are now leg presses."), text("I've changed your plan: squats are now leg presses."))
        val r2 = run(lie, message = "swap squats for leg press please")
        assertEquals("fallback", r2.path)
        assertFalse(r2.text.contains("changed your plan"))
    }

    @Test
    fun `기억이 있으면 프롬프트의 에이전트 구역에 id 와 함께 들어간다`() {
        val section = service(ScriptedModel()).agentSection("はると", listOf(
            CoachMemory(id = 3, userId = 7, category = "schedule", content = "週末はジムに行けない"),
        ))
        assertTrue(section.contains("[memory_id 3, schedule] 週末はジムに行けない"), section)
        assertTrue(section.contains("Act only when asked"))
    }

    @Test
    fun `응급 증상·섭식장애·임신 신호면 도구 없이 단발 경로가 답한다`() {
        every { gemini.prepareChat(any(), any(), any(), any()) } returns GeminiAIService.PreparedChat(
            "ja", PTStyle.SPARTAN, null, null, ChatSafetyService.RiskProfile(locale = "ja", redFlagSymptoms = true),
            ChatComputedFacts.Facts(emptyList()), emptyList(), "", null,
        )
        val model = ScriptedModel(text("should not be called"))
        val r = run(model, message = "胸が締め付けられるように痛い")
        assertEquals("legacy", r.path)
        assertEquals("legacy answer", r.text)
        assertTrue(model.requests.isEmpty())
    }

    @Test
    fun `첫 호출은 12초, 이후 호출은 10초 상한 — 첫 호출이 막히면 남은 시간으로 단발 경로`() {
        val model = ScriptedModel(calls("get_recent_workouts" to "{}"), text("Okay"), text("了解、今日もやるぞ。"))
        run(model)
        assertEquals(listOf(12_000L, 10_000L, 10_000L), model.timeouts, "안전 재생성(언어 불일치) 호출도 10초 상한")

        var now = 0L
        val stuck = ScriptedModel(AgentModelResult.Failure(null, "timeout", 12_000))
        stuck.onCall = { now = 12_000 }
        val r = run(stuck, clock = { now })
        assertEquals("legacy", r.path, "12초 뒤에도 단발 경로를 부를 시간(9초)이 남는다")
    }
}
