package com.richjun.liftupai.domain.ai.agent

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.richjun.liftupai.domain.ai.service.ChatSafetyService
import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.workout.api.ApiTestSupport
import com.richjun.liftupai.domain.workout.search.ExerciseSearchIndexService
import com.richjun.liftupai.domain.workout.service.UserPlanService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.time.ZoneId

/**
 * 11-21 승인 항목 6-a·b·c(실측 model_r3 에서 나온 약점):
 * 6-a 답 끝에 플랜에서 할 일 하나, 6-b 통증 질문은 오늘 플랜부터, 6-c 교체 검색 반복 막기·지어낸 인자 이름 받기.
 */
class AgentQualityRulesTest : ApiTestSupport() {
    @Autowired private lateinit var agent: CoachAgentService
    @Autowired private lateinit var search: SearchExercisesTool
    @Autowired private lateinit var gemini: com.richjun.liftupai.domain.ai.service.GeminiAIService

    private val mapper = jacksonObjectMapper()
    private fun ctx(user: User) = CoachToolContext(user, "ko", ZoneId.of("Asia/Seoul"), ChatSafetyService.RiskProfile(locale = "ko"))

    @Test
    fun `한 답에서 검색은 두 번까지 — 세 번째는 고르거나 물으라는 실패`() {
        val c = ctx(newUser("srch"))
        val q = mapper.readTree("""{"query":"knee friendly leg exercise"}""")
        val first = search.run(c, q); val second = search.run(c, q); val third = search.run(c, q)
        assertEquals(true, first["ok"]); assertEquals(true, second["ok"])
        assertEquals(false, third["ok"]); assertEquals("search_limit", third["error"])
        assertTrue((third["detail"] as String).contains("swap_exercise_in_plan"))
        assertEquals(true, search.run(ctx(newUser("srch2")), q)["ok"], "다른 답(새 문맥)은 다시 처음부터")
    }

    @Test
    fun `교체 도구는 모델이 지어낸 인자 이름도 받는다`() {
        val plans = mockk<UserPlanService>(relaxed = true)
        every { plans.substituteExerciseInActivePlan(any(), any(), any()) } returns 2
        every { plans.substitutionRefusal(any(), any(), any()) } returns null // 사전 검사 통과(relaxed 목은 null 대신 가짜 객체를 돌려준다)
        val tool = SwapExerciseTool(plans, mockk(relaxed = true), mockk(relaxed = true))
        val user = newUser("swp")
        val r1 = tool.run(ctx(user), mapper.readTree("""{"old_exercise_id": 11, "new_exercise_id": 22}"""))
        val r2 = tool.run(ctx(user), mapper.readTree("""{"exercise_id": "33", "replacement_exercise_id": 44}"""))
        val r3 = tool.run(ctx(user), mapper.readTree("""{"from_exercise_id": 55, "to_exercise_id": 66, "old_exercise_id": 1}"""))
        assertEquals(true, r1["ok"]); assertEquals(true, r2["ok"]); assertEquals(true, r3["ok"])
        verify { plans.substituteExerciseInActivePlan(user.id, 11, 22) }
        verify { plans.substituteExerciseInActivePlan(user.id, 33, 44) }
        verify { plans.substituteExerciseInActivePlan(user.id, 55, 66) } // 정식 이름이 우선
        val bad = runCatching { tool.run(ctx(user), mapper.readTree("""{"new_exercise_id": 22}""")) }
        assertTrue(bad.exceptionOrNull() is ToolArgumentException, "바꿀 운동이 없으면 여전히 인자 오류")
    }

    @Test
    fun `프롬프트에 통증·구체성·검색 상한 규칙이 있다`() {
        val p = agent.agentSection("민수", emptyList())
        assertTrue(p.contains("Pain or injury questions") && p.contains("first call get_today_workout"), "6-b 통증 규칙")
        assertTrue(p.contains("ONE concrete next step"), "6-a 구체성 규칙")
        assertTrue(p.contains("at most 2 searches"), "6-c 검색 상한")
    }

    @Test
    fun `에이전트 프롬프트엔 '아무것도 못 바꾼다'·'부상은 설정에서'가 없고 단발 경로엔 그대로`() {
        val single = gemini.buildChatPrompt("허리 아파", "민수", com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN, "ko")
        val agentP = gemini.buildChatPrompt("허리 아파", "민수", com.richjun.liftupai.domain.user.entity.PTStyle.SPARTAN, "ko", agentSection = "## YOU ARE AN AGENT WITH TOOLS")
        assertTrue(single.contains("You CANNOT edit") && single.contains("injury settings"), "단발 경로(도구 없음)는 예전 문장 그대로")
        assertTrue(!agentP.contains("You CANNOT edit") && !agentP.contains("injury settings"), "도구가 있는 경로에 모순 문장이 남았다")
        assertTrue(agentP.contains("ONLY by calling the tools") && agentP.contains("update_my_profile (injuries)"))
    }
}
