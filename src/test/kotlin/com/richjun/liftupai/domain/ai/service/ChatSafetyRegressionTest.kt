package com.richjun.liftupai.domain.ai.service

import com.richjun.liftupai.domain.auth.entity.User
import com.richjun.liftupai.domain.user.entity.BodyInfo
import com.richjun.liftupai.domain.user.entity.UserProfile
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 2026-09-16 버그 헌팅에서 나온 오탐/누락 회귀 테스트. */
class ChatSafetyRegressionTest {
    private val s = ChatSafetyService()
    private val user = User(id = 1L, email = "t@t.t", nickname = "테스터")

    // ── 언어 판정: 비율 기반 ──
    @Test fun `영어 답변에 한글 음식명 하나가 인용돼도 불일치가 아니다`() {
        assertFalse(s.languageMismatch("You logged 'ramen' (라면) for lunch, so you have 1,200 kcal left today. Solid work, keep the protein up.", "en"))
    }
    @Test fun `한국어 답변에 짧은 영어가 섞여도 불일치가 아니다`() {
        assertFalse(s.languageMismatch("OK 👍 3x12로 가고, 무릎은 계속 살펴요.", "ko"))
    }
    @Test fun `양성 대조 - 일본어 사용자에게 한국어로 답하면 불일치`() {
        assertTrue(s.languageMismatch("오늘은 상체 위주로 가볍게 하세요. 무릎은 쉬어야 합니다.", "ja"))
    }
    @Test fun `양성 대조 - 한국어 사용자에게 영어로만 답하면 불일치`() {
        assertTrue(s.languageMismatch("Let's do an upper body day today and rest the knee.", "ko"))
    }

    // ── 부상 운동 권유: "A 대신 B" ──
    @Test fun `스쿼트 대신 레그 익스텐션 권유는 무릎 부상에 위반`() {
        assertTrue(s.recommendsRestrictedExercise("스쿼트 대신 레그 익스텐션을 하세요.", setOf("knee")))
    }
    @Test fun `재활 후 복귀 조건 설명은 권유가 아니다`() {
        assertFalse(s.recommendsRestrictedExercise("무릎 재활이 끝난 뒤에 스쿼트로 돌아가려면 의사 승인이 필요해요. 지금은 하지 마세요.", setOf("knee")))
    }
    @Test fun `양성 대조 - 스쿼트 대신 상체 운동은 통과`() {
        assertFalse(s.recommendsRestrictedExercise("스쿼트 대신 덤벨 벤치프레스를 하세요.", setOf("knee")))
    }

    // ── 계산값: 장비 무게는 체중이 아니다 ──
    private fun profile(weight: Double) = UserProfile(id = 1L, user = user, bodyInfo = BodyInfo(weight = weight, height = 175.0), gender = "male", age = 30)
    private fun facts(msg: String, w: Double = 78.0): String {
        val p = profile(w); val risk = s.assess(p, emptySet(), msg, emptyList(), "ko")
        return ChatComputedFacts.build(p, msg, risk).lines.joinToString("\n")
    }
    @Test fun `30kg 덤벨은 체중으로 오인하지 않는다`() {
        assertFalse(facts("30kg 덤벨로 벤치 몇 세트 해야 해?").contains("30"), facts("30kg 덤벨로 벤치 몇 세트 해야 해?"))
    }
    @Test fun `양성 대조 - 42키로 기준이라고 말하면 체중으로 본다`() {
        assertTrue(facts("42키로 기준으로 한시간 600칼로리 태우는 법").contains("42"), facts("42키로 기준으로 한시간 600칼로리 태우는 법"))
    }

    // ── 섭식장애 신호: 하루 총량 문맥일 때만 ──
    @Test fun `300kcal 간식은 신호가 아니다`() {
        assertFalse(s.assess(profile(60.0), emptySet(), "300kcal 간식 먹어도 돼?", emptyList(), "ko").eatingDisorderCues)
    }
    @Test fun `양성 대조 - 하루 500칼로리는 신호`() {
        assertTrue(s.assess(profile(60.0), emptySet(), "하루 500칼로리만 먹고 운동하면 얼마나 빨리 빠져?", emptyList(), "ko").eatingDisorderCues)
    }
}
